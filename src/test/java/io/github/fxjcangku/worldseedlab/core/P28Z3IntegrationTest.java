package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.CandidateSeedSearch;
import io.github.fxjcangku.worldseedlab.core.solver.LocalSmtSeedSolver;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import io.github.fxjcangku.worldseedlab.core.solver.SolverResearchInput;
import io.github.fxjcangku.worldseedlab.core.solver.SymbolicRandomSpread;

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

        System.out.println("WorldSeedLab P2.8 actual Z3 symbolic solver integration passed.");
    }
}
