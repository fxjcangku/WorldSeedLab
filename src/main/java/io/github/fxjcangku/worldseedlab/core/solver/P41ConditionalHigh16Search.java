package io.github.fxjcangku.worldseedlab.core.solver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * P4.1 · P3.9 真实轮廓 Z3/Java FIT-HOLDOUT 的 low48 模型候选，
 * 与 P4.0 未认证自定义气候前向模型联动，分片搜索每个低48的全部65536高位。
 *
 * <p>本类只检验用户显式提供的生成器假设，不改变 P3.0
 * Full64BiomeCandidateSearch 对 UNVERIFIED_OR_CUSTOM 的拒绝门槛。
 * MATCH 只是条件模型一致；真实服务器生成器、Salt和自然来源仍 UNKNOWN。
 * 不读取客户端区块，不输出世界HMAC和原始坐标，不修改任何观测。
 */
public final class P41ConditionalHigh16Search {
    private P41ConditionalHigh16Search() {}

    public static final int MAX_LOW48 = 16;

    public enum Completion {
        REQUESTED_WINDOW_COMPLETE, EVALUATION_BUDGET, TIME_BUDGET, RESULT_CAP,
        CANCELLED, MODEL_REGISTRY_INCOMPATIBLE
    }

    public record ConditionalCandidate(long fullSeedBits,
                                       int fitMatches, int holdoutMatches) {
        public long low48() { return fullSeedBits & Full64BiomeCandidateSearch.LOW48_MASK; }
        public int high16() { return (int) (fullSeedBits >>> 48); }
        public String hex16() { return "%016x".formatted(fullSeedBits); }
    }

    public record Outcome(Completion completion, long nextIndex,
                          long evaluations, long fitRejected, long holdoutRejected,
                          long domainSize, List<ConditionalCandidate> candidates) {
        public Outcome {
            candidates = List.copyOf(candidates);
        }

        /**
         * 即使真正遍历所有high16，本证明仍是 UNVERIFIED_CUSTOM_MODEL，
         * 不能视为未知远端服务器真实Seed的唯一性证明。
         */
        public boolean fullConditionalDomainChecked(
                Full64BiomeCandidateSearch.SearchBudget budget) {
            return completion == Completion.REQUESTED_WINDOW_COMPLETE
                    && budget.fromIndexInclusive() == 0
                    && budget.toIndexExclusive() == domainSize;
        }
    }

    /**
     * 低48候选由调用者真实P3.9约束求解器提供；本方法再次严格核查数量、
     * Java FIT/HOLDOUT 计数及去重。稳定升序保证在同一候选集上的下标可续算。
     * 但 Z3 未完成时不同会话可能返回不同候选集合，不能跨集合续算。
     */
    public static Outcome search(
            List<LocalSmtSeedSolver.VerifiedLow48> structuralLow48,
            ClientQuartEvidenceArchive.Archive archive,
            P40CustomClimateModel.Specification specification,
            Full64BiomeCandidateSearch.SearchBudget budget,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(structuralLow48);
        Objects.requireNonNull(archive);
        Objects.requireNonNull(specification);
        Objects.requireNonNull(budget);
        Objects.requireNonNull(cancelled);
        if (structuralLow48.isEmpty() || structuralLow48.size() > MAX_LOW48) {
            throw new IllegalArgumentException("只有1..16条Java验证过的结构low48候选可以搜索");
        }
        Set<Long> unique = new HashSet<>();
        for (var low : structuralLow48) {
            if (low == null || low.fitMatches() < 2 || low.holdoutMatches() < 1
                    || !unique.add(low.seedLow48())) {
                throw new IllegalArgumentException("结构低48候选重复或未通过FIT/HOLDOUT");
            }
        }
        var sorted = structuralLow48.stream()
                .sorted(Comparator.comparingLong(LocalSmtSeedSolver.VerifiedLow48::seedLow48))
                .toList();
        var scope = archive.scope();
        if (!"minecraft:overworld".equals(scope.dimension())
                || !scope.gameVersion().equals(System.getProperty("mcVersion", "26.2"))) {
            throw new IllegalArgumentException("生成器研究版本或维度与真实quart证据不匹配");
        }
        long domain = (long) sorted.size() * Full64BiomeCandidateSearch.HIGH16_COUNT;
        if (budget.toIndexExclusive() > domain) {
            throw new IllegalArgumentException("高16位分片越过已验证结构候选范围");
        }
        var observations = archive.samples();
        // Archive.read() 已严格检查 CLIENT_OBSERVED、3 FIT+2 HOLDOUT 和1024方块间距。
        var fits = observations.stream()
                .filter(s -> s.split() == Full64BiomeCandidateSearch.Split.FIT).toList();
        var holdouts = observations.stream()
                .filter(s -> s.split() == Full64BiomeCandidateSearch.Split.HOLDOUT).toList();
        if (fits.size() != 3 || holdouts.size() < 2) {
            throw new IllegalArgumentException("需要严格3FIT和至少2HOLDOUT真实群系");
        }
        if (observations.stream().anyMatch(s ->
                !specification.possibleBiomes().contains(s.observedBiome()))) {
            return new Outcome(Completion.MODEL_REGISTRY_INCOMPATIBLE,
                    budget.fromIndexInclusive(), 0, 0, 0, domain, List.of());
        }
        var predictedScope = new Full64BiomeCandidateSearch.Scope(
                scope.worldFingerprint(), scope.gameVersion(), scope.dimension(),
                specification.generatorProfile());
        var model = P40CustomClimateModel.create(predictedScope, specification);
        if (model.trust() != Full64BiomeCandidateSearch.ModelTrust.UNVERIFIED_OR_CUSTOM) {
            throw new IllegalArgumentException("自定义气候模型不得伪装成已认证后台生成器");
        }
        long next = budget.fromIndexInclusive();
        long tests = 0, rejectedFit = 0, rejectedHoldout = 0;
        long deadline = System.nanoTime() + budget.timeoutMillis() * 1_000_000L;
        Completion state = Completion.REQUESTED_WINDOW_COMPLETE;
        var accepted = new ArrayList<ConditionalCandidate>();
        while (next < budget.toIndexExclusive()) {
            if (tests >= budget.maximumEvaluations()) {
                state = Completion.EVALUATION_BUDGET;
                break;
            }
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                state = Completion.CANCELLED;
                break;
            }
            if (System.nanoTime() >= deadline) {
                state = Completion.TIME_BUDGET;
                break;
            }
            long index = next++;
            tests++;
            var low = sorted.get((int) (index / Full64BiomeCandidateSearch.HIGH16_COUNT));
            long full = Full64BiomeCandidateSearch.compose(
                    low.seedLow48(), (int) (index % Full64BiomeCandidateSearch.HIGH16_COUNT));
            var sampler = Objects.requireNonNull(model.prepare(full), "真实气候前向模型不可为空");
            if (!matches(fits, sampler)) {
                rejectedFit++;
                continue;
            }
            if (!matches(holdouts, sampler)) {
                rejectedHoldout++;
                continue;
            }
            accepted.add(new ConditionalCandidate(full, fits.size(), holdouts.size()));
            if (accepted.size() >= budget.maximumResults()
                    && next < budget.toIndexExclusive()) {
                state = Completion.RESULT_CAP;
                break;
            }
        }
        return new Outcome(state, next, tests, rejectedFit, rejectedHoldout,
                domain, accepted);
    }

    private static boolean matches(
            List<Full64BiomeCandidateSearch.BiomeSample> samples,
            Full64BiomeCandidateSearch.BiomeSampler sampler) {
        for (var point : samples) {
            if (!point.observedBiome().equals(sampler.biomeAtQuart(
                    point.quartX(), point.quartY(), point.quartZ()))) {
                return false;
            }
        }
        return true;
    }
}
