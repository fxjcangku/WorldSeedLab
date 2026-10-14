package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.compatibility.StructureObservation.ChunkBounds;
import io.github.fxjcangku.worldseedlab.core.solver.LocalSmtSeedSolver;
import io.github.fxjcangku.worldseedlab.core.solver.SolverResearchInput;
import io.github.fxjcangku.worldseedlab.core.solver.StartPlacementConstraint;
import io.github.fxjcangku.worldseedlab.core.solver.SymbolicRandomSpread;
import java.util.ArrayList;
import java.util.List;

/**
 * P2.9 真正执行通用 Z3 的几何候选约束测试。
 *
 * <p>校准与足迹是合成已知真值；证明的是“几何候选并集 -> SMT -> Java
 * FIT/HOLDOUT”的代码闭环，不代表来自 Minecraft 实际生成结构的取证。
 */
public final class P29Z3GeometryIntegrationTest {
    private P29Z3GeometryIntegrationTest() {}

    public static void main(String[] args) throws Exception {
        String version = System.getProperty("mcVersion", "26.2");
        var input = P29GeometrySmokeTest.parse(
                P29GeometrySmokeTest.syntheticManifest(version, 4096));
        var outcome = LocalSmtSeedSolver.search(input,
                new SymbolicRandomSpread.Options(3, 60_000, 96), "z3", 12);
        check(outcome.candidates().stream().anyMatch(candidate ->
                        candidate.seedLow48() == P29GeometrySmokeTest.SEED
                                && candidate.fitMatches() == 3
                                && candidate.holdoutMatches() == 2),
                "real Z3 must recover known synthetic low48 with disjoint geometry hypotheses");
        check(outcome.rejectedByExactModel() == 0,
                "SMT geometric unions must agree with original Java forward RNG");
        check(outcome.candidates().stream().allMatch(candidate ->
                        input.constraints().stream().allMatch(observation ->
                                observation.matchesPlacement(candidate.seedLow48(),
                                        input.models().get(observation.structureSetId())))),
                "no disjoint-geometry candidate may bypass exact Java FIT/HOLDOUT");

        // 用一个包络内部、所有朝向候选之外的起点替换 FIT，
        // 强制检查 SMT 与 Java 均不能把包络缺口视为有效起点。
        var all = new ArrayList<>(input.constraints());
        var first = all.getFirst();
        int gap = first.possibleStartChunks().minX() + 2;
        int z = first.possibleStartChunks().minZ();
        all.set(0, StartPlacementConstraint.inferredRegion(
                first.worldFingerprint(), first.gameVersion(), first.dimension(),
                first.structureSetId(), new ChunkBounds(gap, z, gap, z),
                StartPlacementConstraint.Split.FIT));
        var wrong = new SolverResearchInput(input.models(), all,
                new io.github.fxjcangku.worldseedlab.core.solver.CandidateSeedSearch.SearchWindow(
                        P29GeometrySmokeTest.SEED, P29GeometrySmokeTest.SEED + 1,
                        1, 2, 30_000));
        var rejected = LocalSmtSeedSolver.search(wrong,
                new SymbolicRandomSpread.Options(3, 30_000, 96), "z3", 2);
        check(rejected.candidates().isEmpty(),
                "envelope-only false candidate must be excluded by the SMT model");
        check(rejected.status() == LocalSmtSeedSolver.Status.BOUNDED_MODEL_EXHAUSTED,
                "power-of-two bounded exact seed must be UNSAT when its FIT origin is wrong");

        System.out.println("WorldSeedLab P2.9 Z3 disjoint geometry + independent holdout passed: "
                + version);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
