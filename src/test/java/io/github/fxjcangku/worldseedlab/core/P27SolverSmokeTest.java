package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.compatibility.StructureObservation.ChunkBounds;
import io.github.fxjcangku.worldseedlab.core.solver.CandidateSeedSearch;
import io.github.fxjcangku.worldseedlab.core.solver.LegacyLcg48;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadMath;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import io.github.fxjcangku.worldseedlab.core.solver.StartPlacementConstraint;
import io.github.fxjcangku.worldseedlab.core.solver.StartPlacementConstraint.Split;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 纯 Java 数学测试：不借助旧破解模组、不需要在线服务器。 */
public final class P27SolverSmokeTest {
    private static final String WORLD = "a".repeat(64);
    private static final String DIMENSION = "minecraft:overworld";
    private static final String GAME_VERSION = "26.2";

    private P27SolverSmokeTest() {
    }

    public static void main(String[] args) {
        // 随机数精确对照 JDK 原生 48bit LCG，检验拒绝采样及负种子溢出。
        for (long seed : new long[] {0L, 1L, -1L, Long.MIN_VALUE, 1234567891011L}) {
            LegacyLcg48 local = new LegacyLcg48(seed);
            Random reference = new Random(seed);
            for (int bound : new int[] {1, 2, 3, 5, 7, 16, 24, 31, 128, 4096}) {
                for (int i = 0; i < 500; i++) {
                    expect(local.nextInt(bound) == reference.nextInt(bound),
                            "legacy RNG deviated at bound=" + bound);
                }
            }
        }

        RandomSpreadSpec linear = new RandomSpreadSpec(
                "minecraft:desert_pyramids", 32, 8,
                RandomSpreadSpec.Distribution.LINEAR, 14357617);
        RandomSpreadSpec triangular = new RandomSpreadSpec(
                "minecraft:monuments", 24, 5,
                RandomSpreadSpec.Distribution.TRIANGULAR, -832);
        expect(linear.offsetBound() == 24, "linear offset bound");
        expect(RandomSpreadMath.low48(-1L) == LegacyLcg48.MASK, "unsigned low 48");
        expect(RandomSpreadMath.low48Hex(-1L).equals("ffffffffffff"), "12-digit seed hex");
        expectFail(() -> new RandomSpreadSpec(
                "minecraft:test", 10, 10, RandomSpreadSpec.Distribution.LINEAR, 1),
                "invalid spacing must be rejected");
        expectFail(() -> new RandomSpreadSpec(
                "minecraft:test", 0, 0, RandomSpreadSpec.Distribution.LINEAR, 1),
                "zero spacing forbidden");

        long sourceSeed = 12_345L;
        var negatives = RandomSpreadMath.potentialChunk(sourceSeed, -4, -7, linear);
        expect(negatives.regionX(linear) == -4 && negatives.regionZ(linear) == -7,
                "negative coordinates use floorDiv, not truncating division");
        expect(negatives.equals(RandomSpreadMath.potentialFromSourceChunk(
                sourceSeed, (-4 * 32) - 12, (-7 * 32) + 6, linear)),
                "source chunk coordinates use correct negative region");
        expect(RandomSpreadMath.potentialChunk(sourceSeed, -4, -7, linear)
                        .equals(RandomSpreadMath.potentialChunk(
                                sourceSeed + (1L << 48), -4, -7, linear)),
                "high 16 world seed bits are NOT visible to 48bit structure placement");
        expect(!RandomSpreadMath.potentialChunk(sourceSeed, 2, 3, linear)
                        .equals(RandomSpreadMath.potentialChunk(sourceSeed, 2, 3, triangular)),
                "different placement parameters should be distinguishable");

        Map<String, RandomSpreadSpec> models = Map.of(linear.structureSetId(), linear);
        List<StartPlacementConstraint> constraints = new ArrayList<>();
        for (int[][] pair : new int[][][] {
                {{-4, 7}}, {{16, -11}}, {{7, 22}}, {{-13, -9}}, {{33, 18}}
        }) {
            int rx = pair[0][0], rz = pair[0][1];
            var start = RandomSpreadMath.potentialChunk(sourceSeed, rx, rz, linear);
            constraints.add(StartPlacementConstraint.controlledExact(
                    WORLD, GAME_VERSION, DIMENSION, linear.structureSetId(),
                    start.x(), start.z(), constraints.size() < 3 ? Split.FIT : Split.HOLDOUT));
        }

        var full = CandidateSeedSearch.search(models, constraints,
                new CandidateSeedSearch.SearchWindow(0, 50_000, 50_000, 32, 120_000),
                () -> false);
        expect(full.fullyCheckedWindow(), "entire explicit bounded range searched");
        expect(full.candidates().stream().anyMatch(c ->
                        c.seedLow48() == sourceSeed
                                && c.holdoutStatus()
                                    == CandidateSeedSearch.HoldoutStatus.HOLDOUT_MODEL_CONSISTENT),
                "known low48 seed must survive independent placement holdouts");
        expect(full.nextLow48() == 50_000, "next cursor at end of completed window");

        var budget = CandidateSeedSearch.search(models, constraints,
                new CandidateSeedSearch.SearchWindow(0, 50_000, 5_000, 32, 120_000),
                () -> false);
        expect(budget.completion() == CandidateSeedSearch.Completion.EVALUATION_BUDGET,
                "explicit CPU iteration budget enforced");
        expect(budget.evaluations() == 5_000 && budget.nextLow48() == 5_000,
                "resumption must be exact");
        var resumed = CandidateSeedSearch.search(models, constraints,
                budget.window().resume(budget.nextLow48()), () -> false);
        expect(resumed.candidates().stream().anyMatch(x -> x.seedLow48() == sourceSeed),
                "known seed should survive in resumed window");
        var cancelled = CandidateSeedSearch.search(models, constraints,
                CandidateSeedSearch.SearchWindow.bounded(0, 25_000), () -> true);
        expect(cancelled.completion() == CandidateSeedSearch.Completion.CANCELLED
                        && cancelled.evaluations() == 0
                        && cancelled.nextLow48() == 0,
                "cancellation before work must not skip a seed");

        var predicted = RandomSpreadMath.potentialChunk(sourceSeed, 88, -71, linear);
        var bounding = StartPlacementConstraint.inferredRegion(
                WORLD, GAME_VERSION, DIMENSION, linear.structureSetId(),
                new ChunkBounds(predicted.x() - 2, predicted.z() - 2,
                        predicted.x() + 2, predicted.z() + 2),
                Split.FIT);
        expect(bounding.matchesPlacement(sourceSeed, linear),
                "geometry region can include a candidate without faking an exact start");
        expectFail(() -> new StartPlacementConstraint(
                WORLD, GAME_VERSION, DIMENSION, linear.structureSetId(),
                new ChunkBounds(1, 1, 3, 3),
                StartPlacementConstraint.Origin.REFERENCE_FROM_CONTROL_WORLD, Split.FIT),
                "exact controlled reference required");
        expectFail(() -> CandidateSeedSearch.search(models,
                List.of(constraints.getFirst(), constraints.getFirst()),
                CandidateSeedSearch.SearchWindow.bounded(0, 500), () -> false),
                "same structure region may not be counted twice");
        expectFail(() -> CandidateSeedSearch.search(models,
                List.of(constraints.getFirst(), new StartPlacementConstraint(
                        "b".repeat(64), GAME_VERSION, DIMENSION, linear.structureSetId(),
                        constraints.get(1).possibleStartChunks(),
                        StartPlacementConstraint.Origin.REFERENCE_FROM_CONTROL_WORLD, Split.HOLDOUT)),
                CandidateSeedSearch.SearchWindow.bounded(0, 500), () -> false),
                "cross-server structure evidence fails closed");
        expectFail(() -> new CandidateSeedSearch.SearchWindow(
                0, (1L << 48) + 1, 100, 5, 1000),
                "outside-low48 search forbidden");
        System.out.println("WorldSeedLab P2.7 bounded seed solver smoke checks passed.");
    }

    private static void expect(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
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
