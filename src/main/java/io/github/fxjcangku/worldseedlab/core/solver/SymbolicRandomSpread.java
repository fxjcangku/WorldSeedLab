package io.github.fxjcangku.worldseedlab.core.solver;

import io.github.fxjcangku.worldseedlab.core.compatibility.StructureObservation.ChunkBounds;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 独立构造 random_spread Legacy LCG 的 SMT-LIB2 位向量约束。
 *
 * <p>不同于 P2.7 挨个枚举，求解后端可利用已知结构起始区块的位约束反推
 * 低 48 位候选。仅需本机外部标准 SMT-LIB2 求解器；不依赖 SeedCrackerX 源码。
 *
 * <p>对非二的幂边界，nextInt 可能出现拒绝采样。本模型展开有限次数的
 * 拒绝路径，并断言每次调用在规定次数内接受。因此 UNSAT 不构成完整
 * 48 位空间不可满足性的数学证明，结果中的候选仍必须经过精确 Java 复核。
 */
public final class SymbolicRandomSpread {
    private static final long MASK = (1L << 48) - 1;
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long X_MULT = 341873128712L;
    private static final long Z_MULT = 132897987541L;

    private SymbolicRandomSpread() {}

    public record Options(int maxRejectionDraws, long timeoutMillis,
                          int maximumFitRegions) {
        public static final Options DEFAULT = new Options(3, 30_000, 64);

        public Options {
            if (maxRejectionDraws < 1 || maxRejectionDraws > 4
                    || timeoutMillis < 1 || timeoutMillis > 120_000
                    || maximumFitRegions < 1 || maximumFitRegions > 128) {
                throw new IllegalArgumentException("SMT resource budget out of bounds");
            }
        }
    }

    public record Export(String smtLib2, int encodedFitRegions, int fitCount,
                         int independentHoldouts, int rejectionCap,
                         boolean rejectionPathTruncated) {}

    public static Export generate(SolverResearchInput input, Options options,
                                  List<Long> excludedModels) {
        Objects.requireNonNull(input);
        Objects.requireNonNull(options);
        Objects.requireNonNull(excludedModels);
        if (excludedModels.size() > 256) {
            throw new IllegalArgumentException("too many previously excluded candidates");
        }
        validateEvidence(input);

        var script = new Builder(options);
        script.raw("; WorldSeedLab original legacy random-spread bit-vector constraint model");
        script.raw("; 48-bit potential structure placement ONLY; not a 64-bit world Seed");
        script.raw("; FIT samples encoded; HOLDOUT samples verified with exact Java RNG afterward");
        script.raw("(set-logic QF_BV)");
        script.raw("(set-option :produce-models true)");
        script.raw("(set-option :timeout " + options.timeoutMillis() + ")");
        script.raw("(declare-fun seed48 () (_ BitVec 48))");
        var window = input.searchWindow();
        script.raw("(assert (bvuge seed48 " + bv(window.startInclusive()) + "))");
        if (window.endExclusive() < CandidateSeedSearch.TOTAL_48) {
            script.raw("(assert (bvult seed48 " + bv(window.endExclusive()) + "))");
        }
        for (long excluded : excludedModels) {
            if (excluded < 0 || excluded >= CandidateSeedSearch.TOTAL_48) {
                throw new IllegalArgumentException("excluded low48 model is out of range");
            }
            script.raw("(assert (not (= seed48 " + bv(excluded) + ")))");
        }

        int fitRegions = 0;
        int fits = 0;
        int holdouts = 0;
        for (StartPlacementConstraint constraint : input.constraints()) {
            if (constraint.split() == StartPlacementConstraint.Split.HOLDOUT) {
                holdouts++;
                continue;
            }
            fits++;
            RandomSpreadSpec spec = input.models().get(constraint.structureSetId());
            ChunkBounds b = constraint.possibleStartChunks();
            long rx0 = Math.floorDiv(b.minX(), spec.spacing());
            long rx1 = Math.floorDiv(b.maxX(), spec.spacing());
            long rz0 = Math.floorDiv(b.minZ(), spec.spacing());
            long rz1 = Math.floorDiv(b.maxZ(), spec.spacing());
            List<String> permitted = new ArrayList<>();
            for (long rx = rx0; rx <= rx1; rx++) {
                for (long rz = rz0; rz <= rz1; rz++) {
                    if (++fitRegions > options.maximumFitRegions()) {
                        throw new IllegalArgumentException("too many region hypotheses for SMT");
                    }
                    long baseX = rx * spec.spacing();
                    long baseZ = rz * spec.spacing();
                    int lowX = (int) Math.max(0, (long) b.minX() - baseX);
                    int highX = (int) Math.min(spec.offsetBound() - 1L, (long) b.maxX() - baseX);
                    int lowZ = (int) Math.max(0, (long) b.minZ() - baseZ);
                    int highZ = (int) Math.min(spec.offsetBound() - 1L, (long) b.maxZ() - baseZ);
                    if (lowX > highX || lowZ > highZ) continue;
                    String prefix = "fit_" + fits + "_region_" + fitRegions;
                    long regionMixer = rx * X_MULT + rz * Z_MULT + spec.salt();
                    String start = script.word(prefix + "_seed", "(bvxor (bvadd seed48 "
                            + bv(regionMixer) + ") " + bv(MULTIPLIER) + ")");
                    Offset x = script.offset(prefix + "_x", start,
                            spec.offsetBound(), spec.distribution());
                    Offset z = script.offset(prefix + "_z", x.afterState(),
                            spec.offsetBound(), spec.distribution());
                    String matchX = inclusive(x.value(), lowX, highX);
                    String matchZ = inclusive(z.value(), lowZ, highZ);
                    permitted.add("(and " + matchX + " " + matchZ + ")");
                }
            }
            script.raw("(assert " + disjunction(permitted) + ")");
        }
        script.raw("(check-sat)");
        script.raw("(get-value (seed48))");
        return new Export(script.result(), fitRegions, fits, holdouts,
                options.maxRejectionDraws(),
                options.maxRejectionDraws() < Integer.MAX_VALUE
                        && input.models().values().stream()
                            .anyMatch(x -> !isPowerOfTwo(x.offsetBound())));
    }

    /**
     * 强制独立 FIT/HOLDOUT 分组；禁用跨世界、维度、版本和重复 region 证据。
     * 输入必须是明确的起始区块约束，而不是肉眼看到的方块坐标。
     */
    public static void validateEvidence(SolverResearchInput input) {
        Map<String, RandomSpreadSpec> models = input.models();
        List<StartPlacementConstraint> evidence = input.constraints();
        if (models.isEmpty() || models.size() > 32
                || evidence.size() < 3 || evidence.size() > 64) {
            throw new IllegalArgumentException("SMT requires 3..64 scoped structure starts");
        }
        int fit = 0, holdout = 0;
        Set<String> seenRegions = new HashSet<>();
        String fingerprint = evidence.getFirst().worldFingerprint();
        String version = evidence.getFirst().gameVersion();
        String dimension = evidence.getFirst().dimension();
        for (StartPlacementConstraint e : evidence) {
            if (!fingerprint.equals(e.worldFingerprint()) || !version.equals(e.gameVersion())
                    || !dimension.equals(e.dimension())) {
                throw new IllegalArgumentException("cross-world/version/dimension evidence rejected");
            }
            RandomSpreadSpec spec = models.get(e.structureSetId());
            if (spec == null || !spec.structureSetId().equals(e.structureSetId())) {
                throw new IllegalArgumentException("unrecognized structure set");
            }
            if (e.split() == StartPlacementConstraint.Split.FIT) fit++;
            else holdout++;
            ChunkBounds b = e.possibleStartChunks();
            long rx0 = Math.floorDiv(b.minX(), spec.spacing());
            long rx1 = Math.floorDiv(b.maxX(), spec.spacing());
            long rz0 = Math.floorDiv(b.minZ(), spec.spacing());
            long rz1 = Math.floorDiv(b.maxZ(), spec.spacing());
            if ((rx1 - rx0 + 1) * (rz1 - rz0 + 1) > 64) {
                throw new IllegalArgumentException("structure start region exceeds safe limit");
            }
            for (long rx = rx0; rx <= rx1; rx++) {
                for (long rz = rz0; rz <= rz1; rz++) {
                    String identifier = e.structureSetId() + ":" + rx + ":" + rz;
                    if (!seenRegions.add(identifier)) {
                        throw new IllegalArgumentException("overlapping FIT/HOLDOUT structure region");
                    }
                }
            }
        }
        if (fit < 2 || holdout < 1) {
            throw new IllegalArgumentException("at least two FIT and one independent HOLDOUT required");
        }
    }

    private static boolean isPowerOfTwo(int n) {
        return (n & (n - 1)) == 0;
    }

    private static String inclusive(String value, int min, int max) {
        if (min == max) return "(= " + value + " " + bv(min) + ")";
        return "(and (bvuge " + value + " " + bv(min)
                + ") (bvule " + value + " " + bv(max) + "))";
    }

    private static String disjunction(List<String> pieces) {
        if (pieces.isEmpty()) return "false";
        if (pieces.size() == 1) return pieces.getFirst();
        return "(or " + String.join(" ", pieces) + ")";
    }

    private static String bv(long word) {
        return "#x%012x".formatted(word & MASK);
    }

    private record Offset(String value, String afterState) {}

    private static final class Builder {
        private final StringBuilder text = new StringBuilder(16_384);
        private final Options options;

        Builder(Options options) {
            this.options = options;
        }

        void raw(String command) {
            text.append(command).append('\n');
        }

        String word(String name, String expression) {
            raw("(define-fun " + name + " () (_ BitVec 48) " + expression + ")");
            return name;
        }

        String flag(String name, String expression) {
            raw("(define-fun " + name + " () Bool " + expression + ")");
            return name;
        }

        /**
         * nextInt(bound) 会消耗 LCG 状态；非二幂时允许最多 N 次拒绝重抽。
         * 假设全部 N 次都拒绝则显式排除该状态，不能暗中返回错误的余数。
         */
        private Offset nextInt(String prefix, String state, int bound) {
            if (bound <= 0 || bound > 4096) {
                throw new IllegalArgumentException("invalid RNG bound");
            }
            boolean exactOneDraw = isPowerOfTwo(bound);
            int draws = exactOneDraw ? 1 : options.maxRejectionDraws();
            List<String> steps = new ArrayList<>(draws);
            List<String> results = new ArrayList<>(draws);
            List<String> accepts = new ArrayList<>(draws);
            long limit = ((1L << 31) / bound) * bound;
            String current = state;
            for (int i = 0; i < draws; i++) {
                String step = word(prefix + "_step" + i, "(bvadd (bvmul " + current
                        + " " + bv(MULTIPLIER) + ") " + bv(11) + ")");
                String bits = word(prefix + "_bits" + i,
                        "(bvlshr " + step + " " + bv(17) + ")");
                String number = word(prefix + "_number" + i,
                        exactOneDraw
                        ? "(bvlshr (bvmul " + bits + " " + bv(bound) + ") " + bv(31) + ")"
                        : "(bvurem " + bits + " " + bv(bound) + ")");
                steps.add(step);
                results.add(number);
                if (!exactOneDraw) {
                    accepts.add(flag(prefix + "_accept" + i,
                            "(bvult " + bits + " " + bv(limit) + ")"));
                }
                current = step;
            }
            if (!exactOneDraw) {
                raw("(assert " + disjunction(accepts) + ")");
            }
            String resolvedResult = results.getLast();
            String resolvedState = steps.getLast();
            for (int i = draws - 2; i >= 0; i--) {
                resolvedResult = "(ite " + accepts.get(i) + " " + results.get(i)
                        + " " + resolvedResult + ")";
                resolvedState = "(ite " + accepts.get(i) + " " + steps.get(i)
                        + " " + resolvedState + ")";
            }
            return new Offset(word(prefix + "_value", resolvedResult),
                    word(prefix + "_after", resolvedState));
        }

        Offset offset(String prefix, String state, int bound,
                      RandomSpreadSpec.Distribution type) {
            Offset first = nextInt(prefix + "_a", state, bound);
            if (type == RandomSpreadSpec.Distribution.LINEAR) {
                return first;
            }
            Offset second = nextInt(prefix + "_b", first.afterState(), bound);
            String value = word(prefix + "_triangular",
                    "(bvlshr (bvadd " + first.value()
                            + " " + second.value() + ") " + bv(1) + ")");
            return new Offset(value, second.afterState());
        }

        String result() { return text.toString(); }
    }
}
