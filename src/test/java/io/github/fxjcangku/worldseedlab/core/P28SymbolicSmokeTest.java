package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.compatibility.StructureObservation.ChunkBounds;
import io.github.fxjcangku.worldseedlab.core.solver.CandidateSeedSearch;
import io.github.fxjcangku.worldseedlab.core.solver.LocalSmtSeedSolver;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadMath;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import io.github.fxjcangku.worldseedlab.core.solver.SolverResearchInput;
import io.github.fxjcangku.worldseedlab.core.solver.StartPlacementConstraint;
import io.github.fxjcangku.worldseedlab.core.solver.SymbolicRandomSpread;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 不依赖 Z3 安装的 SMT 格式与证据门控测试：保护输入作用域、
 * 独立留出、正负 region、无效模型、用户取消后的安全状态。
 */
public final class P28SymbolicSmokeTest {
    private static final String WORLD = "b".repeat(64);
    private static final String SET_ID = "minecraft:test_random_spread";

    private P28SymbolicSmokeTest() {}

    public static void main(String[] args) {
        var linear = new RandomSpreadSpec(SET_ID, 16, 8,
                RandomSpreadSpec.Distribution.LINEAR, 4321);
        var input = sample(linear, 2731, 0, 65_536);
        var generated = SymbolicRandomSpread.generate(input,
                SymbolicRandomSpread.Options.DEFAULT, List.of());
        check(generated.fitCount() == 3 && generated.independentHoldouts() == 2,
                "FIT/HOLDOUT split is retained");
        check(!generated.rejectionPathTruncated(), "power-of-two RNG has exact one draw");
        check(generated.smtLib2().contains("(declare-fun seed48 () (_ BitVec 48))"),
                "48-bit symbolic seed required");
        check(generated.smtLib2().contains("(check-sat)"),
                "SMT program must request satisfiability");
        check(generated.smtLib2().contains("(get-value (seed48))"),
                "SMT program must request concrete seed");
        check(generated.smtLib2().contains("bvuge seed48"),
                "lower half-open window bound");
        check(generated.smtLib2().contains("bvult seed48"),
                "upper half-open window bound");
        check(!generated.smtLib2().contains("_accept"),
                "power-of-two RNG must not introduce artificial rejection paths");
        check(input.constraints().stream().allMatch(e ->
                e.matchesPlacement(2731, linear)), "known synthetic seed fits all controls");

        var nonPower = new RandomSpreadSpec(SET_ID, 32, 8,
                RandomSpreadSpec.Distribution.TRIANGULAR, -9325);
        var triangles = SymbolicRandomSpread.generate(sample(nonPower, 2731, 0, 65_536),
                new SymbolicRandomSpread.Options(3, 20_000, 64), List.of(2713L));
        check(triangles.smtLib2().contains("bvurem"), "non-power-of-two must use modulus");
        check(triangles.smtLib2().contains("triangular"), "triangle must consume 2 draws");
        check(triangles.rejectionPathTruncated(),
                "non-power-of-two loop is bounded, UNSAT is not conclusive");
        check(triangles.smtLib2().contains("(not (= seed48 #x000000000a99))"),
                "previous model must be explicitly blocked");

        check(LocalSmtSeedSolver.parseModel("sat\n((seed48 #x000000000aab))") == 2731,
                "parse hexadecimal SMT model");
        check(LocalSmtSeedSolver.parseModel("sat\n((seed48 #b"
                + "0".repeat(36) + "101010101011))") == 2731,
                "parse exact binary SMT model");
        check(LocalSmtSeedSolver.parseModel("sat\n((seed48 (_ bv2731 48)))") == 2731,
                "parse standard decimal 48-bit model");
        expectFail(() -> LocalSmtSeedSolver.parseModel("unsat"), "missing model is an error");
        expectFail(() -> SymbolicRandomSpread.generate(input,
                SymbolicRandomSpread.Options.DEFAULT, List.of(-1L)),
                "block list cannot contain out-of-range seeds");

        var duplicate = new ArrayList<>(input.constraints());
        duplicate.set(4, duplicate.get(0));
        expectFail(() -> SymbolicRandomSpread.generate(
                new SolverResearchInput(input.models(), duplicate, input.searchWindow()),
                SymbolicRandomSpread.Options.DEFAULT, List.of()),
                "duplicated region between FIT and HOLDOUT must be rejected");

        var mixed = new ArrayList<>(input.constraints());
        var existing = mixed.get(4);
        mixed.set(4, new StartPlacementConstraint("c".repeat(64),
                existing.gameVersion(), existing.dimension(), existing.structureSetId(),
                existing.possibleStartChunks(), existing.origin(), existing.split()));
        expectFail(() -> SymbolicRandomSpread.generate(
                new SolverResearchInput(input.models(), mixed, input.searchWindow()),
                SymbolicRandomSpread.Options.DEFAULT, List.of()),
                "cross-world evidence cannot enter symbolic constraints");
        var withoutHoldout = new ArrayList<>(input.constraints().subList(0, 3));
        expectFail(() -> SymbolicRandomSpread.generate(
                new SolverResearchInput(input.models(), withoutHoldout, input.searchWindow()),
                SymbolicRandomSpread.Options.DEFAULT, List.of()),
                "symbolic model requires independent holdout evidence");

        System.out.println("WorldSeedLab P2.8 symbolic constraints and provenance smoke checks passed.");
    }

    static SolverResearchInput sample(RandomSpreadSpec spec, long seed,
                                      long fromLow48, long toLow48) {
        List<StartPlacementConstraint> observations = new ArrayList<>();
        for (int[] region : new int[][] {
                {-6, 9}, {12, -16}, {21, 17}, {-24, -13}, {8, 31}
        }) {
            var trueChunk = RandomSpreadMath.potentialChunk(
                    seed, region[0], region[1], spec);
            observations.add(StartPlacementConstraint.controlledExact(
                    WORLD, "26.2", "minecraft:overworld", spec.structureSetId(),
                    trueChunk.x(), trueChunk.z(),
                    observations.size() < 3
                            ? StartPlacementConstraint.Split.FIT
                            : StartPlacementConstraint.Split.HOLDOUT));
        }
        return new SolverResearchInput(Map.of(spec.structureSetId(), spec),
                observations, new CandidateSeedSearch.SearchWindow(
                fromLow48, toLow48, 100_000, 32, 20_000));
    }

    static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    static void expectFail(Runnable command, String message) {
        try {
            command.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
