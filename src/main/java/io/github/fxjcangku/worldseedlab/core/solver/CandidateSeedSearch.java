
package io.github.fxjcangku.worldseedlab.core.solver;

import io.github.fxjcangku.worldseedlab.core.compatibility.StructureObservation.ChunkBounds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 可恢复进度的结构种子候选窗口求解器。
 *
 * <p>这是精确的区间枚举与独立留出证据过滤，而不是“自动反转所有 48 位”。
 * 默认单轮最多搜索 100 万个低 48 位候选，CPU 时间和结果数量均有硬上限。
 * 调用方必须放在独立工作线程，禁止在 Minecraft tick / GUI 绘制时执行。
 *
 * <p>重要：任何结果都仅称 LOW_48_CANDIDATE，不能解释成服务器真实 64 位 Seed。
 * 当前模型不包括自定义生成器、额外盐值、结构生物群系检查或频率减免规则。
 */
public final class CandidateSeedSearch {
    public static final long TOTAL_48 = 1L << 48;
    public static final int DEFAULT_BUDGET = 1_000_000;

    private CandidateSeedSearch() {
    }

    /** 参数为半开区间：startInclusive <= seed < endExclusive。 */
    public record SearchWindow(
            long startInclusive,
            long endExclusive,
            int maximumEvaluations,
            int maximumResults,
            long timeoutMillis
    ) {
        public SearchWindow {
            if (startInclusive < 0 || startInclusive >= TOTAL_48
                    || endExclusive <= startInclusive || endExclusive > TOTAL_48) {
                throw new IllegalArgumentException("search must be inside the low-48 range");
            }
            if (maximumEvaluations < 1 || maximumEvaluations > 10_000_000
                    || maximumResults < 1 || maximumResults > 256
                    || timeoutMillis < 1 || timeoutMillis > 120_000) {
                throw new IllegalArgumentException("search CPU/result/time budgets out of range");
            }
        }

        public static SearchWindow bounded(long startInclusive, long endExclusive) {
            return new SearchWindow(startInclusive, endExclusive,
                    DEFAULT_BUDGET, 64, 15_000);
        }

        public SearchWindow resume(long nextLow48) {
            if (nextLow48 < startInclusive || nextLow48 >= endExclusive) {
                throw new IllegalArgumentException("resume position outside search window");
            }
            return new SearchWindow(nextLow48, endExclusive,
                    maximumEvaluations, maximumResults, timeoutMillis);
        }
    }

    public enum Completion {
        FULL_WINDOW_CHECKED,
        EVALUATION_BUDGET,
        RESULT_CAP,
        TIME_BUDGET,
        CANCELLED
    }

    public enum HoldoutStatus {
        NO_HOLDOUT,
        HOLDOUT_MODEL_CONSISTENT
    }

    public record Low48Candidate(long seedLow48, int fitConstraints,
                                 int passedHoldoutConstraints, HoldoutStatus holdoutStatus) {
        public Low48Candidate {
            if (seedLow48 < 0 || seedLow48 >= TOTAL_48) {
                throw new IllegalArgumentException("candidate outside 48 bits");
            }
            Objects.requireNonNull(holdoutStatus);
        }

        public String hex12() {
            return RandomSpreadMath.low48Hex(seedLow48);
        }
    }

    /**
     * nextLow48 是确切的下一个尚未计算的种子；使用它恢复搜索，不重复或跳过。
     * fullyChecked 只针对这一次请求的指定窗口，绝不代表搜尽所有 2^48 种子。
     */
    public record SearchResult(
            SearchWindow window,
            Completion completion,
            long evaluations,
            long nextLow48,
            long holdoutRejected,
            List<Low48Candidate> candidates
    ) {
        public SearchResult {
            candidates = List.copyOf(candidates);
            if (evaluations < 0 || nextLow48 < window.startInclusive()
                    || nextLow48 > window.endExclusive()) {
                throw new IllegalArgumentException("invalid candidate-search progress");
            }
        }

        public boolean fullyCheckedWindow() {
            return completion == Completion.FULL_WINDOW_CHECKED;
        }
    }

    /**
     * 求解仅用 FIT，留出样本只验证命中后的候选。
     *
     * @param models 由目标版本实际结构集合配置得来的参数；禁止套猜测盐值
     * @param observations 包含精确受控起点或可信的几何候选起始范围，不能是玩家站位
     * @param cancelled 用户显式中止；调用者可安全地用 nextLow48 恢复
     */
    public static SearchResult search(
            Map<String, RandomSpreadSpec> models,
            List<StartPlacementConstraint> observations,
            SearchWindow window,
            BooleanSupplier cancelled
    ) {
        Objects.requireNonNull(models);
        Objects.requireNonNull(observations);
        Objects.requireNonNull(window);
        Objects.requireNonNull(cancelled);

        if (observations.size() < 2 || observations.size() > 128 || models.isEmpty()
                || models.size() > 32) {
            throw new IllegalArgumentException("search requires 2..128 constraints and 1..32 models");
        }
        if (models.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("null placement model");
        }
        for (var e : models.entrySet()) {
            if (!e.getKey().equals(e.getValue().structureSetId())) {
                throw new IllegalArgumentException("structure set key does not match model ID");
            }
        }

        String world = observations.getFirst().worldFingerprint();
        String gameVersion = observations.getFirst().gameVersion();
        String dimension = observations.getFirst().dimension();
        List<MatchTask> fits = new ArrayList<>();
        List<MatchTask> holdouts = new ArrayList<>();
        Set<String> independentRegions = new HashSet<>();
        for (StartPlacementConstraint constraint : observations) {
            if (!constraint.worldFingerprint().equals(world)
                    || !constraint.gameVersion().equals(gameVersion)
                    || !constraint.dimension().equals(dimension)) {
                throw new IllegalArgumentException("mixed version, world or dimension evidence");
            }
            RandomSpreadSpec model = models.get(constraint.structureSetId());
            if (model == null) {
                throw new IllegalArgumentException("no model for structure set: "
                        + constraint.structureSetId());
            }
            ChunkBounds b = constraint.possibleStartChunks();
            int fromRx = Math.floorDiv(b.minX(), model.spacing());
            int toRx = Math.floorDiv(b.maxX(), model.spacing());
            int fromRz = Math.floorDiv(b.minZ(), model.spacing());
            int toRz = Math.floorDiv(b.maxZ(), model.spacing());
            long count = ((long) toRx - fromRx + 1) * ((long) toRz - fromRz + 1);
            if (count > 64) {
                throw new IllegalArgumentException("unbounded structure start region");
            }
            // 防止同一区域同时作为训练与留出：同一结构集合的重复观察并不独立。
            for (long rx = fromRx; rx <= toRx; rx++) {
                for (long rz = fromRz; rz <= toRz; rz++) {
                    String tag = model.structureSetId() + ":" + rx + ":" + rz;
                    if (!independentRegions.add(tag)) {
                        throw new IllegalArgumentException(
                                "overlapping structure region between evidence samples");
                    }
                }
            }
            MatchTask task = new MatchTask(constraint, model, count);
            if (constraint.split() == StartPlacementConstraint.Split.FIT) {
                fits.add(task);
            } else {
                holdouts.add(task);
            }
        }
        if (fits.isEmpty()) {
            throw new IllegalArgumentException("at least one FIT constraint required");
        }
        // 确定性排序：面积更小的候选起点先过滤，使大规模扫描更快。
        fits.sort(Comparator.comparingLong(MatchTask::area)
                .thenComparing(x -> x.constraint().structureSetId()));

        List<Low48Candidate> candidates = new ArrayList<>();
        long iterations = 0, seed = window.startInclusive(), rejectedHoldouts = 0;
        Completion completion = Completion.FULL_WINDOW_CHECKED;
        long deadline = System.nanoTime() + window.timeoutMillis() * 1_000_000L;
        while (seed < window.endExclusive()) {
            if (iterations >= window.maximumEvaluations()) {
                completion = Completion.EVALUATION_BUDGET;
                break;
            }
            if ((iterations & 1023L) == 0) {
                if (cancelled.getAsBoolean()) {
                    completion = Completion.CANCELLED;
                    break;
                }
                if (System.nanoTime() >= deadline) {
                    completion = Completion.TIME_BUDGET;
                    break;
                }
            }
            long trial = seed++;
            iterations++;
            boolean matching = true;
            for (MatchTask task : fits) {
                if (!task.constraint().matchesPlacement(trial, task.spec())) {
                    matching = false;
                    break;
                }
            }
            if (!matching) continue;

            int passedHoldout = 0;
            for (MatchTask task : holdouts) {
                if (!task.constraint().matchesPlacement(trial, task.spec())) {
                    matching = false;
                    break;
                }
                passedHoldout++;
            }
            if (!matching) {
                rejectedHoldouts++;
                continue;
            }
            candidates.add(new Low48Candidate(trial, fits.size(), passedHoldout,
                    holdouts.isEmpty() ? HoldoutStatus.NO_HOLDOUT
                            : HoldoutStatus.HOLDOUT_MODEL_CONSISTENT));
            if (candidates.size() >= window.maximumResults()) {
                completion = Completion.RESULT_CAP;
                break;
            }
        }
        if (seed >= window.endExclusive()) {
            completion = Completion.FULL_WINDOW_CHECKED;
        }
        return new SearchResult(window, completion, iterations, seed,
                rejectedHoldouts, candidates);
    }

    private record MatchTask(StartPlacementConstraint constraint,
                             RandomSpreadSpec spec, long area) {
    }
}
