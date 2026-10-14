package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.compatibility.StructureObservation.ChunkBounds;
import io.github.fxjcangku.worldseedlab.core.geometry.StructureFootprintInverter;
import io.github.fxjcangku.worldseedlab.core.solver.CandidateSeedSearch;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadMath;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import io.github.fxjcangku.worldseedlab.core.solver.SolverResearchInput;
import io.github.fxjcangku.worldseedlab.core.solver.StartPlacementConstraint;
import io.github.fxjcangku.worldseedlab.core.solver.SymbolicRandomSpread;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * P2.9 正反例：结构轮廓只能经显式受控校准得到离散候选起点，
 * 保留不同朝向的空白缺口，不能自动从 P2.6 材料指纹得出真起点。
 *
 * <p>这里的起点与完整外边界来自合成夹具，并非真实世界生成的 StructureStart。
 */
public final class P29GeometrySmokeTest {
    static final long SEED = 2731L;
    static final String WORLD = "d".repeat(64);
    static final String SET_ID = "minecraft:research_set";
    static final String STRUCTURE_ID = "minecraft:desert_pyramid";
    static final String DIMENSION = "minecraft:overworld";
    static final RandomSpreadSpec SPEC = new RandomSpreadSpec(
            SET_ID, 32, 16, RandomSpreadSpec.Distribution.LINEAR, 4321);
    private static final int[] OFFSET = {0, -9, 27, 21};
    private static final int[][] REGIONS = {
            {-6, 9}, {12, -16}, {21, 17}, {-24, -13}, {8, 31}
    };

    private P29GeometrySmokeTest() {}

    public static void main(String[] args) throws Exception {
        for (String version : new String[] {"26.1.2", "26.2"}) {
            String manifest = syntheticManifest(version, 4096);
            var input = parse(manifest);
            check(input.constraints().size() == 5, "expected 3 FIT and 2 HOLDOUT");
            for (var observation : input.constraints()) {
                check(observation.origin() == StartPlacementConstraint.Origin.GEOMETRY_ESTIMATE,
                        "geometry result is not authoritative structure start");
                check(observation.alternativeStartChunks().size() == 2,
                        "two controlled orientation alternatives are preserved");
                check(observation.matchesPlacement(SEED, SPEC),
                        "known seed must satisfy all geometry variants");
                check(observation.possibleStartChunks().maxX()
                        - observation.possibleStartChunks().minX() == 4,
                        "outer envelope spans separate starts");
                check(!observation.alternativeStartChunks().stream()
                        .anyMatch(b -> b.contains(observation.possibleStartChunks().minX() + 2,
                                observation.possibleStartChunks().minZ())),
                        "gap must remain absent from exact alternatives");
            }
            var result = CandidateSeedSearch.search(input.models(), input.constraints(),
                    new CandidateSeedSearch.SearchWindow(SEED, SEED + 1, 1, 8, 10_000),
                    () -> false);
            check(result.candidates().stream().anyMatch(candidate ->
                    candidate.seedLow48() == SEED
                            && candidate.passedHoldoutConstraints() == 2),
                    "Java verifier lost known seed across geometry FIT/HOLDOUT");
            var smt = SymbolicRandomSpread.generate(input,
                    SymbolicRandomSpread.Options.DEFAULT, List.of());
            check(smt.fitCount() == 3 && smt.independentHoldouts() == 2,
                    "geometry FIT/HOLDOUT split is still independent");
            check(smt.smtLib2().contains("(or "),
                    "non-contiguous alternatives must become SMT disjunctions");

            expectFail(() -> parseUnchecked(manifest.replace(
                    "constraint.0.geometry.quality=COMPLETE_PERIMETER",
                    "constraint.0.geometry.quality=MATERIAL_CLUSTER_ONLY")),
                    "P2.6 material scan is not complete footprint");
            expectFail(() -> parseUnchecked(manifest.replace(
                    "constraint.0.geometry.quality=COMPLETE_PERIMETER",
                    "constraint.0.geometry.quality=PARTIAL_OR_OCCLUDED")),
                    "partial footprint must not silently yield an origin");
            expectFail(() -> parseUnchecked(manifest.replace(
                    "constraint.0.origin=GEOMETRY_ESTIMATE",
                    "constraint.0.origin=REFERENCE_FROM_CONTROL_WORLD")),
                    "no geometry can masquerade as proven exact structure start");
            expectFail(() -> parseUnchecked(manifest.replace(
                    "constraint.0.geometry.calibration.0.referenceKind=CONTROLLED_TRUE_STRUCTURE_START",
                    "constraint.0.geometry.calibration.0.referenceKind=UNVERIFIED_SIGHTING")),
                    "no manual sighting may be used as a calibration");
            expectFail(() -> parseUnchecked(manifest.replace(
                    "constraint.0.geometry.calibration.0.gameVersion=" + version,
                    "constraint.0.geometry.calibration.0.gameVersion=1.19.4")),
                    "cross-version calibration must fail closed");
            expectFail(() -> parseUnchecked(manifest.replace(
                    "constraint.0.geometry.calibration.0.structureId=" + STRUCTURE_ID,
                    "constraint.0.geometry.calibration.0.structureId=minecraft:shipwreck")),
                    "different structures within one set may not share a geometry template");
            expectFail(() -> parseUnchecked(manifest + "constraint.0.minChunkX=0\n"),
                    "no ambiguous coexistence of manual region and geometry");
            expectFail(() -> parseUnchecked(manifest.replace(
                    "constraint.0.geometry.calibrations.count=2",
                    "constraint.0.geometry.calibrations.count=100")),
                    "bound calibration count and parser input");
        }

        // 校验 floorDiv/ceilDiv 的负坐标，保留精确起点。
        var calibration = StructureFootprintInverter.Calibration.fromControlled(
                "control_negative", "26.2", DIMENSION, SET_ID, STRUCTURE_ID, "north",
                footprint(-20, -31, OFFSET), -20, -31, 0);
        var negative = new StructureFootprintInverter.ObservedFootprint(
                WORLD, "26.2", DIMENSION, SET_ID, STRUCTURE_ID,
                footprint(-17, -29, OFFSET),
                StructureFootprintInverter.FootprintQuality.COMPLETE_PERIMETER);
        var inferred = StructureFootprintInverter.infer(negative, List.of(calibration));
        check(inferred.alternatives().equals(
                List.of(new ChunkBounds(-17, -29, -17, -29))),
                "negative chunk positions must invert exactly");

        // 外包范围可能包含预测区块，但区块不在任何朝向候选中，不得误报。
        StartPlacementConstraint first = parse(syntheticManifest("26.2", 4096))
                .constraints().getFirst();
        int gapX = first.possibleStartChunks().minX() + 2;
        int trueZ = first.possibleStartChunks().minZ();
        check(first.possibleStartChunks().contains(gapX, trueZ),
                "gap must exist inside the display-only envelope");
        check(first.alternativeStartChunks().stream().noneMatch(b -> b.contains(gapX, trueZ)),
                "gap must not be represented by any hypothesis");
        boolean witnessedEnvelopeFalsePositive = false;
        for (long probe = 0; probe < 80_000; probe++) {
            var placement = RandomSpreadMath.potentialChunk(probe,
                    REGIONS[0][0], REGIONS[0][1], SPEC);
            if (placement.x() == gapX && placement.z() == trueZ) {
                check(!first.matchesPlacement(probe, SPEC),
                        "Java candidate validation must reject an envelope-only false positive");
                witnessedEnvelopeFalsePositive = true;
                break;
            }
        }

        check(witnessedEnvelopeFalsePositive,
                "fixture must actually contain a negative seed landing in the display-only gap");

        // 非二幂 nextInt 的接受条件应保护选中的 Region，而非强制所有候选朝向。
        var nonPower = new RandomSpreadSpec(SET_ID, 32, 8,
                RandomSpreadSpec.Distribution.TRIANGULAR, -91);
        var symbolicNonPower = SymbolicRandomSpread.generate(
                new SolverResearchInput(Map.of(SET_ID, nonPower),
                        parse(syntheticManifest("26.2", 4096)).constraints(),
                        CandidateSeedSearch.SearchWindow.bounded(0, 4096)),
                new SymbolicRandomSpread.Options(3, 20_000, 96), List.of());
        check(symbolicNonPower.smtLib2().contains("_accept"),
                "bounded rejection guard should appear in non-power-of-two SMT");
        check(symbolicNonPower.smtLib2().lines().noneMatch(line ->
                        line.startsWith("(assert (or fit_")),
                "unselected region rejection guards must not become unconditional assertions");

        checkFailureForUnsupportedScope();
        System.out.println("WorldSeedLab P2.9 geometry alternatives and offline parsing passed.");
    }

    static String syntheticManifest(String version, long endExclusive) {
        StringBuilder text = new StringBuilder();
        add(text, "worldFingerprint", WORLD);
        add(text, "gameVersion", version);
        add(text, "dimension", DIMENSION);
        add(text, "models.count", "1");
        add(text, "model.0.id", SET_ID);
        add(text, "model.0.spacing", SPEC.spacing());
        add(text, "model.0.separation", SPEC.separation());
        add(text, "model.0.distribution", SPEC.distribution());
        add(text, "model.0.salt", SPEC.salt());
        add(text, "constraints.count", REGIONS.length);
        for (int i = 0; i < REGIONS.length; i++) {
            var start = RandomSpreadMath.potentialChunk(
                    SEED, REGIONS[i][0], REGIONS[i][1], SPEC);
            String p = "constraint." + i + ".";
            add(text, p + "structureSet", SET_ID);
            add(text, p + "origin", "GEOMETRY_ESTIMATE");
            add(text, p + "split", i < 3 ? "FIT" : "HOLDOUT");
            add(text, p + "geometry.quality", "COMPLETE_PERIMETER");
            add(text, p + "geometry.structureId", STRUCTURE_ID);
            addFootprint(text, p + "geometry.", footprint(start.x(), start.z(), OFFSET));
            add(text, p + "geometry.calibrations.count", 2);
            for (int variant = 0; variant < 2; variant++) {
                String c = p + "geometry.calibration." + variant + ".";
                add(text, c + "referenceKind", "CONTROLLED_TRUE_STRUCTURE_START");
                add(text, c + "referenceId", "synthetic_control_" + i + "_" + variant);
                add(text, c + "variant", "orientation_" + variant);
                add(text, c + "gameVersion", version);
                add(text, c + "dimension", DIMENSION);
                add(text, c + "structureSet", SET_ID);
                add(text, c + "structureId", STRUCTURE_ID);
                int rx = variant == 0 ? 12 : -12;
                int rz = variant == 0 ? -13 : 14;
                int[] offsets = variant == 0 ? OFFSET
                        : new int[] {OFFSET[0] - 64, OFFSET[1],
                                OFFSET[2] - 64, OFFSET[3]};
                addFootprint(text, c + "control.", footprint(rx, rz, offsets));
                add(text, c + "control.trueStartChunkX", rx);
                add(text, c + "control.trueStartChunkZ", rz);
                add(text, c + "toleranceBlocks", 0);
            }
        }
        add(text, "search.startLow48", 0);
        add(text, "search.endExclusive", endExclusive);
        add(text, "search.maxEvaluations", 4096);
        add(text, "search.maxResults", 32);
        add(text, "search.timeoutMillis", 60_000);
        return text.toString();
    }

    static SolverResearchInput parse(String text) throws Exception {
        Path file = Files.createTempFile("worldseedlab-p29-", ".properties");
        try {
            Files.writeString(file, text);
            return SolverResearchInput.load(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static SolverResearchInput parseUnchecked(String text) {
        try {
            return parse(text);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("invalid test manifest", failure);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid test manifest", failure);
        }
    }

    static StructureFootprintInverter.BlockBounds footprint(int chunkX, int chunkZ, int[] offset) {
        int x = Math.multiplyExact(chunkX, 16);
        int z = Math.multiplyExact(chunkZ, 16);
        return new StructureFootprintInverter.BlockBounds(x + offset[0], z + offset[1],
                x + offset[2], z + offset[3]);
    }

    private static void addFootprint(
            StringBuilder out, String prefix, StructureFootprintInverter.BlockBounds bounds
    ) {
        add(out, prefix + "minBlockX", bounds.minX());
        add(out, prefix + "minBlockZ", bounds.minZ());
        add(out, prefix + "maxBlockX", bounds.maxX());
        add(out, prefix + "maxBlockZ", bounds.maxZ());
    }

    private static void add(StringBuilder out, String key, Object value) {
        out.append(key).append('=').append(value).append('\n');
    }

    private static void checkFailureForUnsupportedScope() {
        var calibration = StructureFootprintInverter.Calibration.fromControlled(
                "controlled", "26.1.2", DIMENSION, SET_ID, STRUCTURE_ID, "north",
                footprint(2, 3, OFFSET), 2, 3, 0);
        var target = new StructureFootprintInverter.ObservedFootprint(
                WORLD, "26.2", DIMENSION, SET_ID, STRUCTURE_ID,
                footprint(4, 5, OFFSET),
                StructureFootprintInverter.FootprintQuality.COMPLETE_PERIMETER);
        expectFail(() -> StructureFootprintInverter.infer(target, List.of(calibration)),
                "cross-version reference must not be promoted");
    }

    private static void check(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }

    private static void expectFail(Runnable attempt, String failure) {
        try {
            attempt.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(failure);
    }
}
