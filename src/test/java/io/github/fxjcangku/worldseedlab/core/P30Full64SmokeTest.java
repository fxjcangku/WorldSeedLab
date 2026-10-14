package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch.BiomeSample;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch.Scope;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch.Split;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch.Origin;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch.SearchBudget;
import io.github.fxjcangku.worldseedlab.core.solver.LocalSmtSeedSolver;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadMath;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import java.util.List;
import java.util.ArrayList;

/**
 * P3.0 完整64位候选空间、符号位、精确恢复游标和证据隔离测试。
 *
 * <p>当前离线 oracle 使用 Mojang 真实 XoroshiroRandomSource 数学，但把随机输出
 * 人工转换成测试 ID，绝非 Minecraft 实际生物群系生成；只能验证“高16位
 * 加独立证据筛选”引擎，不能据此宣布恢复真实服务器的完整 Seed。
 */
public final class P30Full64SmokeTest {
    private static final long CONTROL_FULL64 = 0xBEEF000000000AABL;
    private static final long CONTROL_LOW48 = 2731L;
    private static final String PROFILE = "worldseedlab:controlled_full64_rng";
    private static final String WORLD = "9".repeat(64);
    private static final int[][] POINTS = {
            {-3121, 23, 1907}, {951, -12, -3170}, {12, 48, 1024},
            {5422, 7, -6009}
    };

    private P30Full64SmokeTest() {}

    public static void main(String[] args) {
        check(Full64BiomeCandidateSearch.compose(CONTROL_LOW48, 0xBEEF)
                        == CONTROL_FULL64, "assemble exact full64 signed bits");
        check(Long.toString(CONTROL_FULL64).startsWith("-"), "signed full64 representation");
        check(Full64BiomeCandidateSearch.compose(
                        Full64BiomeCandidateSearch.LOW48_MASK, 65535) == -1L,
                "all 64 bits set must equal signed -1");
        check(Full64BiomeCandidateSearch.compose(0, 0) == 0, "all zeros");
        expectFail(() -> Full64BiomeCandidateSearch.compose(CONTROL_LOW48, 65536),
                "reject more than 16 high bits");
        expectFail(() -> Full64BiomeCandidateSearch.compose(1L << 48, 0),
                "reject extra low48 high bits");

        var spec = new RandomSpreadSpec("minecraft:desert_pyramids", 32, 8,
                RandomSpreadSpec.Distribution.LINEAR, 14357617);
        var reference = RandomSpreadMath.potentialChunk(CONTROL_FULL64, -121, 52, spec);
        check(reference.equals(RandomSpreadMath.potentialChunk(CONTROL_LOW48, -121, 52, spec)),
                "structure placement alone provably cannot distinguish high16");
        for (String version : new String[] {"26.1.2", "26.2"}) {
            Scope scope = new Scope(WORLD, version, "minecraft:overworld", PROFILE);
            var model = new ControlRngModel(scope);
            List<BiomeSample> data = data(scope, CONTROL_FULL64);
            var low = List.of(new LocalSmtSeedSolver.VerifiedLow48(CONTROL_LOW48, 2, 1));
            var full = new SearchBudget(0, Full64BiomeCandidateSearch.HIGH16_COUNT,
                    65_536, 120_000, 64);
            var complete = Full64BiomeCandidateSearch.search(
                    low, data, model, full, () -> false);
            check(complete.completion()
                            == Full64BiomeCandidateSearch.Completion.REQUESTED_WINDOW_COMPLETE,
                    "full 65536 high16 controlled domain should complete");
            check(complete.evaluations() == 65_536
                            && complete.nextIndex() == 65_536,
                    "every full64 high16 candidate was inspected once");
            check(complete.candidates().size() == 1
                            && complete.candidates().getFirst().fullSeedBits() == CONTROL_FULL64,
                    "independent high16-sensitive CONTROL oracle must recover exact 64 bit pattern");
            check(complete.oneModelCandidateInFullyCheckedDomain(full),
                    "one candidate for supplied low48 in fully checked high16 domain");
            var candidate = complete.candidates().getFirst();
            check(candidate.low48() == CONTROL_LOW48 && candidate.high16Unsigned() == 0xBEEF,
                    "preserve unsigned high16 and low48");
            check(candidate.hex16().equals("beef000000000aab")
                            && candidate.signedDecimal().equals(Long.toString(CONTROL_FULL64)),
                    "unsigned hexadecimal and signed decimal report");
            check(complete.modelTrust()
                            == Full64BiomeCandidateSearch.ModelTrust.CONTROLLED_EXPERIMENT_ONLY,
                    "control RNG MUST never be treated as verified actual biome model");

            var prefix = Full64BiomeCandidateSearch.search(low, data, model,
                    new SearchBudget(0, 1000, 1000, 15_000, 64), () -> false);
            check(prefix.candidates().isEmpty() && prefix.nextIndex() == 1000,
                    "exact resume offset before BEEF");
            var suffix = Full64BiomeCandidateSearch.search(low, data, model,
                    new SearchBudget(prefix.nextIndex(), 65_536, 65_536, 120_000, 64),
                    () -> false);
            check(suffix.candidates().stream().anyMatch(c -> c.fullSeedBits() == CONTROL_FULL64),
                    "resume should not skip known high16 seed");
            var cancelled = Full64BiomeCandidateSearch.search(low, data, model,
                    full, () -> true);
            check(cancelled.completion() == Full64BiomeCandidateSearch.Completion.CANCELLED
                            && cancelled.nextIndex() == 0 && cancelled.evaluations() == 0,
                    "cancel must not skip a candidate");

            var structureOnly = new Full64BiomeCandidateSearch.BiomeModel() {
                public Full64BiomeCandidateSearch.ModelTrust trust() {
                    return Full64BiomeCandidateSearch.ModelTrust.UNVERIFIED_OR_CUSTOM;
                }
                public Scope scope() { return scope; }
                public Full64BiomeCandidateSearch.BiomeSampler prepare(long seed) {
                    return (x, y, z) -> "minecraft:plains";
                }
            };
            expectFail(() -> Full64BiomeCandidateSearch.search(
                    low, data, structureOnly, full, () -> false),
                    "no fake full64 conclusion from structure-only or unknown generator");
            expectFail(() -> Full64BiomeCandidateSearch.search(
                    low, List.of(), model, full, () -> false),
                    "no high16 evidence must fail closed");
            expectFail(() -> Full64BiomeCandidateSearch.search(
                    low, data.stream().filter(s -> s.split() == Split.FIT).toList(),
                    model, full, () -> false),
                    "no independent biome HOLDOUT must fail closed");
            var duplicate = new ArrayList<>(data);
            duplicate.set(3, data.getFirst());
            expectFail(() -> Full64BiomeCandidateSearch.search(
                    low, duplicate, model, full, () -> false),
                    "duplicate FIT/HOLDOUT sample cannot count as independent");
            var mixed = new ArrayList<>(data);
            BiomeSample sample = mixed.getLast();
            mixed.set(3, new BiomeSample(
                    new Scope("1".repeat(64), version, "minecraft:overworld", PROFILE),
                    sample.quartX(), sample.quartY(), sample.quartZ(),
                    sample.observedBiome(), sample.split(), sample.origin()));
            expectFail(() -> Full64BiomeCandidateSearch.search(
                    low, mixed, model, full, () -> false), "cross-world sample must be rejected");
            var invalid = new ArrayList<>(data);
            invalid.set(3, new BiomeSample(scope, sample.quartX(), sample.quartY(),
                    sample.quartZ(), sample.observedBiome(), sample.split(),
                    Origin.CLIENT_OBSERVED));
            expectFail(() -> Full64BiomeCandidateSearch.search(
                    low, invalid, model, full, () -> false),
                    "cannot mix controlled truth with actual client evidence");
            List<BiomeSample> clientSamples = data.stream().map(samplePoint ->
                    new BiomeSample(scope, samplePoint.quartX(), samplePoint.quartY(),
                            samplePoint.quartZ(), samplePoint.observedBiome(),
                            samplePoint.split(), Origin.CLIENT_OBSERVED)).toList();
            expectFail(() -> Full64BiomeCandidateSearch.search(
                    low, clientSamples, model, full, () -> false),
                    "client observations cannot use demonstration-only seed oracle");

            var shortBudget = Full64BiomeCandidateSearch.search(low, data, model,
                    new SearchBudget(0, 65_536, 17, 20_000, 64), () -> false);
            check(shortBudget.completion()
                            == Full64BiomeCandidateSearch.Completion.EVALUATION_BUDGET
                            && shortBudget.nextIndex() == 17,
                    "time/CPU budget must return exact progress");
        }
        System.out.println("WorldSeedLab P3.0 full64 HIGH16 domain, Mojang Xoroshiro control,"
                + " scope isolation, cancellation and exact resume passed. "
                + "CONTROL ONLY; real biome model and remote 64bit seed not yet verified.");
    }

    static List<BiomeSample> data(Scope scope, long seed) {
        List<BiomeSample> out = new ArrayList<>();
        for (int i = 0; i < POINTS.length; i++) {
            int[] p = POINTS[i];
            out.add(new BiomeSample(scope, p[0], p[1], p[2],
                    pseudoBiome(seed, p[0], p[1], p[2]),
                    i < 3 ? Split.FIT : Split.HOLDOUT,
                    Origin.CONTROL_WORLD_OBSERVED));
        }
        return List.copyOf(out);
    }

    static String pseudoBiome(long seed, int x, int y, int z) {
        long salt = ((long) x * 341873128712L)
                ^ ((long) z * 132897987541L) ^ ((long) y * 0x9E3779B97F4A7C15L);
        // 这是 Mojang 原生的 XoroshiroRandomSource，不是实际生物群系气候噪声。
        // 测试标签是人为指定的类别，不允许上传为真实客户端观测数据。
        int value = new XoroshiroRandomSource(seed ^ salt).nextInt(1 << 20);
        return "worldseedlab:control_" + value;
    }

    private record ControlRngModel(Scope scope)
            implements Full64BiomeCandidateSearch.BiomeModel {
        public Full64BiomeCandidateSearch.ModelTrust trust() {
            return Full64BiomeCandidateSearch.ModelTrust.CONTROLLED_EXPERIMENT_ONLY;
        }
        public Full64BiomeCandidateSearch.BiomeSampler prepare(long seed) {
            return (x, y, z) -> pseudoBiome(seed, x, y, z);
        }
    }

    private static void check(boolean yes, String message) {
        if (!yes) throw new AssertionError(message);
    }
    private static void expectFail(Runnable attempt, String message) {
        try {
            attempt.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
