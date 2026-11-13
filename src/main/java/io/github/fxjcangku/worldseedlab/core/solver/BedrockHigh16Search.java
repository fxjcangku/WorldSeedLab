package io.github.fxjcangku.worldseedlab.core.solver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * 基岩条件模型的高16位搜索内核；完整遍历任意long的高位，而非只枚举Random.nextLong可达种子。
 *
 * <p>此纯数学内核不读取客户端或档案。调用者必须先审计low48来源、世界/版本/维度、
 * 生成时期和跨维度共用Seed假设；本类不会把任意传入low48认证成真实服务器种子。
 * FIT和HOLDOUT都只接受基岩阳性，阴性方块不具有随机规则为假的天然资格。
 */
public final class BedrockHigh16Search {
    private BedrockHigh16Search() {}

    /** 候选域只包含输入的一个low48；全域完成不代表其它low48已被排除。 */
    public record Outcome(Full64BiomeCandidateSearch.Completion completion,
                          long evaluations, long nextIndex, long holdoutRejected,
                          List<Full64BiomeCandidateSearch.Candidate64> candidates) {
        public Outcome {
            candidates = List.copyOf(candidates);
        }

        public boolean completeHigh16Domain(Full64BiomeCandidateSearch.SearchBudget budget) {
            return completion == Full64BiomeCandidateSearch.Completion.REQUESTED_WINDOW_COMPLETE
                    && budget.fromIndexInclusive() == 0
                    && budget.toIndexExclusive() == Full64BiomeCandidateSearch.HIGH16_COUNT
                    && evaluations == Full64BiomeCandidateSearch.HIGH16_COUNT;
        }
    }

    /**
     * 每个候选先通过FIT，再检查从未参与FIT的HOLDOUT。不同坐标不自动证明自然来源，
     * 因此本方法只返回条件匹配，不能单独作为公开“破解成功”的依据。
     */
    public static Outcome search(long low48, BedrockRandomModel.GradientRule rule,
                                 List<BedrockRandomModel.PositiveBlock> fit,
                                 List<BedrockRandomModel.PositiveBlock> holdout,
                                 Full64BiomeCandidateSearch.SearchBudget budget,
                                 BooleanSupplier cancelled) {
        Objects.requireNonNull(rule);
        Objects.requireNonNull(budget);
        Objects.requireNonNull(cancelled);
        // 防止调用者在后台修改列表，使分片计数对应的证据随计算变化。
        fit = List.copyOf(fit);
        holdout = List.copyOf(holdout);
        if (low48 < 0 || low48 > Full64BiomeCandidateSearch.LOW48_MASK
                || budget.toIndexExclusive() > Full64BiomeCandidateSearch.HIGH16_COUNT) {
            throw new IllegalArgumentException("基岩low48或高16搜索域非法");
        }
        if (fit.size() < 32 || holdout.size() < 16 || fit.size() + holdout.size() > 4096) {
            throw new IllegalArgumentException("需要至少32个FIT、16个HOLDOUT，合计最多4096个基岩阳性点");
        }
        var unique = new HashSet<BedrockRandomModel.PositiveBlock>();
        var fitChunks = new HashSet<Long>();
        var holdoutChunks = new HashSet<Long>();
        for (var point : fit) validatePoint(rule, point, unique, fitChunks);
        for (var point : holdout) validatePoint(rule, point, unique, holdoutChunks);
        if (fitChunks.size() < 2 || holdoutChunks.isEmpty()
                || holdoutChunks.stream().anyMatch(fitChunks::contains)) {
            throw new IllegalArgumentException("基岩FIT至少来自两个区块，HOLDOUT须来自其它区块");
        }

        long index = budget.fromIndexInclusive(), evaluated = 0, rejected = 0;
        long deadline = System.nanoTime() + budget.timeoutMillis() * 1_000_000L;
        var completion = Full64BiomeCandidateSearch.Completion.REQUESTED_WINDOW_COMPLETE;
        var accepted = new ArrayList<Full64BiomeCandidateSearch.Candidate64>();
        while (index < budget.toIndexExclusive()) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                completion = Full64BiomeCandidateSearch.Completion.CANCELLED;
                break;
            }
            if (evaluated >= budget.maximumEvaluations()) {
                completion = Full64BiomeCandidateSearch.Completion.EVALUATION_BUDGET;
                break;
            }
            if (System.nanoTime() >= deadline) {
                completion = Full64BiomeCandidateSearch.Completion.TIME_BUDGET;
                break;
            }
            long seed = Full64BiomeCandidateSearch.compose(low48, (int) index);
            index++;
            evaluated++;
            try {
                if (!BedrockRandomModel.matchesPositive(seed, BedrockRandomModel.Algorithm.XOROSHIRO,
                        rule, fit)) continue;
                if (!BedrockRandomModel.matchesPositive(seed, BedrockRandomModel.Algorithm.XOROSHIRO,
                        rule, holdout)) {
                    rejected++;
                    continue;
                }
            } catch (CancellationException interruptedDuringCandidate) {
                // 当前候选尚未完整校验，下次必须从它重跑，不能跳过未完成的域。
                index--;
                evaluated--;
                completion = Full64BiomeCandidateSearch.Completion.CANCELLED;
                break;
            }
            accepted.add(new Full64BiomeCandidateSearch.Candidate64(seed, fit.size(), holdout.size()));
            if (accepted.size() >= budget.maximumResults()) {
                completion = Full64BiomeCandidateSearch.Completion.RESULT_CAP;
                break;
            }
        }
        // 最后一个候选恰好达到数量上限仍已完成请求窗口，不丢失已完成事实。
        if (index == budget.toIndexExclusive()) {
            completion = Full64BiomeCandidateSearch.Completion.REQUESTED_WINDOW_COMPLETE;
        }
        return new Outcome(completion, evaluated, index, rejected, accepted);
    }

    private static void validatePoint(BedrockRandomModel.GradientRule rule,
                                      BedrockRandomModel.PositiveBlock point,
                                      HashSet<BedrockRandomModel.PositiveBlock> unique,
                                      HashSet<Long> chunks) {
        if (!unique.add(point)) throw new IllegalArgumentException("基岩FIT/HOLDOUT含重复坐标");
        // 两端确定为true/false的高度没有随机信息，不能充数达到样本下限。
        if (point.y() <= rule.trueAtAndBelow() || point.y() >= rule.falseAtAndAbove()) {
            throw new IllegalArgumentException("样本不在显式基岩规则的随机高度范围");
        }
        long chunk = ((long) Math.floorDiv(point.x(), 16) << 32)
                | (Math.floorDiv(point.z(), 16) & 0xffffffffL);
        chunks.add(chunk);
    }
}
