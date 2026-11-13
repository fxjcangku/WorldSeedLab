package io.github.fxjcangku.worldseedlab.core.solver;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * 未知 Salt 的单结构集合研究：只求 E48=(worldSeed+salt) mod 2^48。
 *
 * <p>Legacy random_spread 先把 Seed、Region 混合值及 Salt 相加，再初始化
 * 48 位 LCG。同一结构集合的所有位置只能约束这个和，无法唯一拆分世界
 * Seed 与 Salt。将模型 Salt 置零只是在选择 E48 坐标系，并不假设服务端
 * Salt 为零；spacing、separation、分布方式和自然来源仍须独立确认。
 *
 * <p>结果使用独立类型，不能直接传给要求 VerifiedLow48 的高16位求解器。
 * 所有作用域、独立 FIT/HOLDOUT、离散几何并集和精确 Java RNG 校验均复用
 * 原有求解器；本类不读取世界、不启动客户端采集，也不推测 Salt。
 */
public final class EffectiveStructureStateSearch {
    public static final String RESULT_KIND = "EFFECTIVE_STRUCTURE_STATE_ONLY";

    private EffectiveStructureStateSearch() {}

    /** 仅为结构放置有效状态；字段不称为 seedLow48，避免误接完整种子流程。 */
    public record VerifiedEffectiveState(long effectiveState48,
                                         int fitMatches, int holdoutMatches) {
        public VerifiedEffectiveState {
            if (effectiveState48 < 0 || effectiveState48 >= CandidateSeedSearch.TOTAL_48
                    || fitMatches < 2 || holdoutMatches < 1) {
                throw new IllegalArgumentException("有效结构状态必须属于48位域并通过独立FIT/HOLDOUT");
            }
        }

        public String hex12() {
            return RandomSpreadMath.low48Hex(effectiveState48);
        }
    }

    public record Outcome(LocalSmtSeedSolver.Status status, int solverCalls,
                          long rejectedByHoldout, long rejectedByExactModel,
                          List<VerifiedEffectiveState> candidates,
                          boolean incompleteRejectionPaths) {
        public Outcome {
            candidates = List.copyOf(candidates);
        }
    }

    /** 受控对照用的精确模加法；Java long 溢出后低48位仍与数学模加法一致。 */
    public static long effectiveState(long worldSeed, int salt) {
        return RandomSpreadMath.low48(worldSeed + salt);
    }

    /**
     * 输入窗口属于 E48 域。只支持一个结构集合，归零 Salt 是显式建模约定；
     * 不同结构集合若各有未知 Salt，不能共享同一个 E48 变量。
     */
    public static Outcome search(SolverResearchInput effectiveInput,
                                 SymbolicRandomSpread.Options options,
                                 String z3Executable, int maximumModels) throws IOException {
        Objects.requireNonNull(effectiveInput);
        if (effectiveInput.models().size() != 1
                || effectiveInput.models().values().iterator().next().salt() != 0) {
            throw new IllegalArgumentException("E48模式只接受一个显式Salt归零的结构集合");
        }
        SymbolicRandomSpread.validateEvidence(effectiveInput);
        var result = LocalSmtSeedSolver.search(effectiveInput, options,
                z3Executable, maximumModels);
        var states = result.candidates().stream().map(candidate ->
                new VerifiedEffectiveState(candidate.seedLow48(),
                        candidate.fitMatches(), candidate.holdoutMatches())).toList();
        return new Outcome(result.status(), result.solverCalls(), result.rejectedByHoldout(),
                result.rejectedByExactModel(), states, result.incompleteRejectionPaths());
    }
}
