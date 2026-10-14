package io.github.fxjcangku.worldseedlab.core.solver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * P3.0 完整64位候选研究：从已通过结构约束的低48位候选枚举高16位，
 * 使用独立三维群系证据筛选。注意：没有可信、匹配版本的完整世界生成模型，
 * 单靠结构起点无法区分高16位，本类会拒绝以结构数据冒充群系证据。
 *
 * <p>本类绝不自行读取服务端后台 Seed，也不在 Fabric Tick/GUI 调用。
 * 用户要显式从离线研究线程提供模型和真实已加载区块观测。
 * 即便找到唯一模型候选，也不自动声称得到了未知服务器真实64位 Seed。
 */
public final class Full64BiomeCandidateSearch {
    public static final int HIGH16_COUNT = 1 << 16;
    public static final long LOW48_MASK = (1L << 48) - 1;
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private Full64BiomeCandidateSearch() {}

    public record Scope(String worldFingerprint, String gameVersion,
                        String dimension, String generatorProfile) {
        public Scope {
            if (worldFingerprint == null || !HASH.matcher(worldFingerprint).matches()
                    || (!"26.1.2".equals(gameVersion) && !"26.2".equals(gameVersion))
                    || !id(dimension) || !id(generatorProfile)) {
                throw new IllegalArgumentException("无效世界、版本、维度或生成器指纹");
            }
        }
    }

    public enum Split { FIT, HOLDOUT }
    public enum Origin { CLIENT_OBSERVED, CONTROL_WORLD_OBSERVED }

    /**
     * quart 坐标，每轴四方块；只允许客户端确实观察到的原版群系注册键。
     * 不接受群系注册表“存在但没实际生成”的信息作为匹配证据。
     */
    public record BiomeSample(Scope scope, int quartX, int quartY, int quartZ,
                              String observedBiome, Split split, Origin origin) {
        public BiomeSample {
            Objects.requireNonNull(scope);
            if (!id(observedBiome)) throw new IllegalArgumentException("非法群系注册键");
            Objects.requireNonNull(split);
            Objects.requireNonNull(origin);
        }
    }

    /** 模型实现必须显式说明来源；结构 RNG 不属于完整64位群系生成模型。 */
    public enum ModelTrust {
        VERIFIED_TARGET_MINECRAFT_WORLDGEN,
        CONTROLLED_EXPERIMENT_ONLY,
        UNVERIFIED_OR_CUSTOM
    }

    /** 对一个完整64位候选只创建一次上下文，多处 FIT/HOLDOUT 复用同一生成器实例。 */
    @FunctionalInterface
    public interface BiomeSampler {
        String biomeAtQuart(int quartX, int quartY, int quartZ);
    }

    public interface BiomeModel {
        ModelTrust trust();
        Scope scope();
        BiomeSampler prepare(long full64Seed);
    }

    public record SearchBudget(long fromIndexInclusive, long toIndexExclusive,
                               long maximumEvaluations, long timeoutMillis,
                               int maximumResults) {
        public SearchBudget {
            if (fromIndexInclusive < 0 || toIndexExclusive <= fromIndexInclusive
                    || maximumEvaluations < 1 || maximumEvaluations > 10_000_000
                    || timeoutMillis < 1 || timeoutMillis > 120_000
                    || maximumResults < 1 || maximumResults > 128) {
                throw new IllegalArgumentException("高16位搜索范围或预算非法");
            }
        }
    }

    public enum Completion {
        REQUESTED_WINDOW_COMPLETE,
        EVALUATION_BUDGET,
        TIME_BUDGET,
        RESULT_CAP,
        CANCELLED
    }

    /** signedDecimal 是 Minecraft /seed 常见的 Java long 有符号形式。 */
    public record Candidate64(long fullSeedBits, int fitMatches, int holdoutMatches) {
        public int high16Unsigned() {
            return (int) ((fullSeedBits >>> 48) & 0xffffL);
        }
        public long low48() {
            return fullSeedBits & LOW48_MASK;
        }
        public String hex16() {
            return "%016x".formatted(fullSeedBits);
        }
        public String signedDecimal() {
            return Long.toString(fullSeedBits);
        }
    }

    public record Outcome(Completion completion, ModelTrust modelTrust,
                          long evaluations, long nextIndex, long holdoutRejected,
                          long domainSize, List<Candidate64> candidates) {
        public Outcome {
            candidates = List.copyOf(candidates);
        }

        /** 仅描述输入模型与搜索窗口，不证明后台真实种子、Salt 和自定义生成器。 */
        public boolean oneModelCandidateInFullyCheckedDomain(SearchBudget budget) {
            return completion == Completion.REQUESTED_WINDOW_COMPLETE
                    && budget.fromIndexInclusive() == 0
                    && budget.toIndexExclusive() == domainSize
                    && candidates.size() == 1;
        }
    }

    /** 不允许高16位截断误差；作为 long 位图重组，不因符号位导致丢失高位。 */
    public static long compose(long low48, int high16Unsigned) {
        if (low48 < 0 || low48 > LOW48_MASK
                || high16Unsigned < 0 || high16Unsigned >= HIGH16_COUNT) {
            throw new IllegalArgumentException("low48/high16 超出无符号位域");
        }
        return ((long) high16Unsigned << 48) | low48;
    }

    public static Outcome search(
            List<LocalSmtSeedSolver.VerifiedLow48> structuralCandidates,
            List<BiomeSample> samples, BiomeModel model,
            SearchBudget budget, BooleanSupplier cancelled
    ) {
        Objects.requireNonNull(structuralCandidates);
        Objects.requireNonNull(samples);
        Objects.requireNonNull(model);
        Objects.requireNonNull(budget);
        Objects.requireNonNull(cancelled);
        if (structuralCandidates.isEmpty() || structuralCandidates.size() > 32) {
            throw new IllegalArgumentException("需要1..32个已通过结构FIT/HOLDOUT的low48候选");
        }
        Set<Long> dedup = new HashSet<>();
        for (var low : structuralCandidates) {
            Objects.requireNonNull(low);
            if (low.fitMatches() < 2 || low.holdoutMatches() < 1
                    || !dedup.add(low.seedLow48())) {
                throw new IllegalArgumentException("重复或未通过独立复核的low48候选");
            }
        }
        if (model.trust() == null || model.trust() == ModelTrust.UNVERIFIED_OR_CUSTOM) {
            throw new IllegalArgumentException("未知生成器模型不得给出64位候选");
        }
        Scope scope = Objects.requireNonNull(model.scope());
        if (samples.size() < 3 || samples.size() > 64) {
            throw new IllegalArgumentException("完整64位搜索必须有3..64条独立群系证据");
        }
        List<BiomeSample> fits = new ArrayList<>();
        List<BiomeSample> holdouts = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        Origin origin = samples.getFirst().origin();
        for (BiomeSample sample : samples) {
            Objects.requireNonNull(sample);
            if (!scope.equals(sample.scope()) || sample.origin() != origin) {
                throw new IllegalArgumentException("跨世界/维度/版本/生成器/证据来源混合已拒绝");
            }
            String position = sample.quartX() + ":" + sample.quartY() + ":" + sample.quartZ();
            if (!unique.add(position)) {
                throw new IllegalArgumentException("重复 quart 采样点不能同时算作训练和留出");
            }
            if (sample.split() == Split.FIT) fits.add(sample);
            else holdouts.add(sample);
        }
        if (fits.size() < 2 || holdouts.isEmpty()) {
            throw new IllegalArgumentException("至少两条群系 FIT + 一条独立 HOLDOUT");
        }
        if (origin == Origin.CLIENT_OBSERVED
                && model.trust() != ModelTrust.VERIFIED_TARGET_MINECRAFT_WORLDGEN) {
            throw new IllegalArgumentException("真实客户端群系必须使用与目标版本一致的已验证生成器");
        }
        long domainSize = (long) structuralCandidates.size() * HIGH16_COUNT;
        if (budget.toIndexExclusive() > domainSize) {
            throw new IllegalArgumentException("搜索窗口越过候选 high16 整体位域");
        }

        long index = budget.fromIndexInclusive();
        long tested = 0, rejectedByHoldout = 0;
        Completion finish = Completion.REQUESTED_WINDOW_COMPLETE;
        List<Candidate64> found = new ArrayList<>();
        long deadline = System.nanoTime() + budget.timeoutMillis() * 1_000_000L;
        while (index < budget.toIndexExclusive()) {
            if (tested >= budget.maximumEvaluations()) {
                finish = Completion.EVALUATION_BUDGET;
                break;
            }
            // 对昂贵的世界生成器，每次候选前都允许取消和检查总时间预算。
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                finish = Completion.CANCELLED;
                break;
            }
            if (System.nanoTime() >= deadline) {
                finish = Completion.TIME_BUDGET;
                break;
            }
            long candidateIndex = index++;
            tested++;
            long low = structuralCandidates.get((int) (candidateIndex / HIGH16_COUNT)).seedLow48();
            long bits = compose(low, (int) (candidateIndex % HIGH16_COUNT));
            BiomeSampler sampler = Objects.requireNonNull(model.prepare(bits),
                    "生成器未提供64位群系采样器");
            if (!matches(sampler, fits)) continue;
            if (!matches(sampler, holdouts)) {
                rejectedByHoldout++;
                continue;
            }
            found.add(new Candidate64(bits, fits.size(), holdouts.size()));
            if (found.size() >= budget.maximumResults()) {
                finish = Completion.RESULT_CAP;
                break;
            }
        }
        if (index >= budget.toIndexExclusive()) {
            finish = Completion.REQUESTED_WINDOW_COMPLETE;
        }
        return new Outcome(finish, model.trust(), tested, index,
                rejectedByHoldout, domainSize, found);
    }

    private static boolean matches(BiomeSampler sampler, List<BiomeSample> samples) {
        for (BiomeSample sample : samples) {
            String predicted = sampler.biomeAtQuart(
                    sample.quartX(), sample.quartY(), sample.quartZ());
            if (!sample.observedBiome().equals(predicted)) return false;
        }
        return true;
    }

    private static boolean id(String value) {
        return value != null && value.length() <= 256 && ID.matcher(value).matches();
    }
}
