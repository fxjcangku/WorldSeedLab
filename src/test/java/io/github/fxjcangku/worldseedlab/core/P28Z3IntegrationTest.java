package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.CandidateSeedSearch;
import io.github.fxjcangku.worldseedlab.core.solver.LocalSmtSeedSolver;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import io.github.fxjcangku.worldseedlab.core.solver.SolverResearchInput;
import io.github.fxjcangku.worldseedlab.core.solver.SymbolicRandomSpread;
import io.github.fxjcangku.worldseedlab.core.solver.EffectiveStructureStateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.StartPlacementConstraint;
import java.util.ArrayList;
import java.util.Map;

/**
 * 真正调用通用 Z3 引擎验证 SMT 模型可执行。
 *
 * <p>测试全部使用已知的“合成世界结构起点”，不能用通过结果
 * 声称在未知服务器中恢复了 Seed。CI 必须显式安装独立 Z3 二进制。
 */
public final class P28Z3IntegrationTest {
    private P28Z3IntegrationTest() {}

    public static void main(String[] args) throws Exception {
        long knownSeed = 2731L;
        var linear = new RandomSpreadSpec("minecraft:test_random_spread",
                16, 8, RandomSpreadSpec.Distribution.LINEAR, 4321);
        SolverResearchInput bounded = P28SymbolicSmokeTest.sample(
                linear, knownSeed, 0, 4096);
        var result = LocalSmtSeedSolver.search(bounded,
                new SymbolicRandomSpread.Options(3, 30_000, 64), "z3", 8);
        P28SymbolicSmokeTest.check(result.candidates().stream()
                        .anyMatch(x -> x.seedLow48() == knownSeed && x.holdoutMatches() == 2),
                "SMT backend must recover the exact low48 seed in a bounded 4096-space test");
        P28SymbolicSmokeTest.check(result.rejectedByExactModel() == 0,
                "SMT power-of-two RNG must agree with exact Java verifier");
        P28SymbolicSmokeTest.check(result.candidates().stream().allMatch(x ->
                        x.fitMatches() == 3 && x.holdoutMatches() == 2),
                "every accepted model must pass FIT and independent HOLDOUT");

        // Non-power-of-two nextInt and triangular distribution:
        // tight domain forces the known synthetic seed, exercising bounded rejection encoding.
        var triangular = new RandomSpreadSpec("minecraft:test_random_spread",
                32, 8, RandomSpreadSpec.Distribution.TRIANGULAR, -91);
        var nonPower = LocalSmtSeedSolver.search(
                P28SymbolicSmokeTest.sample(triangular, knownSeed, knownSeed, knownSeed + 1),
                new SymbolicRandomSpread.Options(3, 30_000, 64), "z3", 3);
        P28SymbolicSmokeTest.check(nonPower.candidates().stream()
                        .anyMatch(x -> x.seedLow48() == knownSeed),
                "triangular + non-power-of-two model must accept exact legacy RNG truth");

        // A genuinely wrong exact candidate seed must not be accepted as another world seed.
        var wrongWindow = LocalSmtSeedSolver.search(
                P28SymbolicSmokeTest.sample(linear, knownSeed, knownSeed + 1, knownSeed + 2),
                new SymbolicRandomSpread.Options(3, 20_000, 64), "z3", 2);
        P28SymbolicSmokeTest.check(wrongWindow.candidates().isEmpty(),
                "incorrect exact seed must not magically satisfy independent starts");

        // 原始坐标仍来自已声明的合成控制模型；求解器只得到Salt归零的E48变量，
        // 不把Seed和Salt分别传入新接口，也不把结果送进高16搜索。
        var zeroSalt = new RandomSpreadSpec(linear.structureSetId(),
                linear.spacing(), linear.separation(), linear.distribution(), 0);
        var effectiveInput = new SolverResearchInput(Map.of(zeroSalt.structureSetId(), zeroSalt),
                bounded.constraints(), new CandidateSeedSearch.SearchWindow(
                        0, 16384, 100_000, 32, 30_000));
        var effective = EffectiveStructureStateSearch.search(effectiveInput,
                new SymbolicRandomSpread.Options(3, 30_000, 64), "z3", 8);
        long expectedEffective = EffectiveStructureStateSearch.effectiveState(knownSeed, linear.salt());
        P28SymbolicSmokeTest.check(effective.candidates().stream().anyMatch(candidate ->
                        candidate.effectiveState48() == expectedEffective
                                && candidate.fitMatches() == 3 && candidate.holdoutMatches() == 2),
                "真实Z3必须恢复Seed与未知Salt之和，并保留独立FIT/HOLDOUT");
        P28SymbolicSmokeTest.check(effective.candidates().stream().noneMatch(candidate ->
                        candidate.effectiveState48() == knownSeed),
                "未知Salt有效状态不得误报为控制世界low48");
        P28SymbolicSmokeTest.check(effective.rejectedByExactModel() == 0,
                "有效状态仍须经过原始Java RNG精确复核");

        var wrongEffectiveInput = new SolverResearchInput(effectiveInput.models(),
                effectiveInput.constraints(), new CandidateSeedSearch.SearchWindow(
                        knownSeed, knownSeed + 1, 1, 2, 20_000));
        var wrongEffective = EffectiveStructureStateSearch.search(wrongEffectiveInput,
                new SymbolicRandomSpread.Options(3, 20_000, 64), "z3", 2);
        P28SymbolicSmokeTest.check(wrongEffective.candidates().isEmpty(),
                "将世界Seed错误当作E48的受控窗口必须被Z3拒绝");

        // 正确FIT配上不相符的独立HOLDOUT：必须由Java留出复核淘汰，
        // 不能把HOLDOUT也偷偷放入SMT训练后仅以UNSAT冒充独立验证。
        var conflictingHoldout = new ArrayList<>(effectiveInput.constraints());
        var oldHoldout = conflictingHoldout.get(3);
        var oldChunk = oldHoldout.possibleStartChunks();
        conflictingHoldout.set(3, StartPlacementConstraint.controlledExact(
                oldHoldout.worldFingerprint(), oldHoldout.gameVersion(), oldHoldout.dimension(),
                oldHoldout.structureSetId(), oldChunk.minX() + 1, oldChunk.minZ(),
                StartPlacementConstraint.Split.HOLDOUT));
        var holdoutInput = new SolverResearchInput(effectiveInput.models(), conflictingHoldout,
                new CandidateSeedSearch.SearchWindow(
                        expectedEffective, expectedEffective + 1, 1, 2, 20_000));
        var rejectedHoldout = EffectiveStructureStateSearch.search(holdoutInput,
                new SymbolicRandomSpread.Options(3, 20_000, 64), "z3", 2);
        P28SymbolicSmokeTest.check(rejectedHoldout.candidates().isEmpty()
                        && rejectedHoldout.rejectedByHoldout() == 1,
                "未知Salt有效状态必须保留真正独立的HOLDOUT拒绝统计");

        System.out.println("WorldSeedLab P2.8 actual Z3 symbolic solver integration passed.");
    }
}
