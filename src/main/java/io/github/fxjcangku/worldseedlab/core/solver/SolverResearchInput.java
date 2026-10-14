package io.github.fxjcangku.worldseedlab.core.solver;

import io.github.fxjcangku.worldseedlab.core.compatibility.StructureObservation.ChunkBounds;
import io.github.fxjcangku.worldseedlab.core.geometry.StructureFootprintInverter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * 离线种子候选实验配置加载器。输入必须包含明确的结构集合参数与
 * 起始区块约束，不接收 P2.6 的材料候选或普通玩家目击坐标。
 *
 * <p>属性文件由研究者显式提供，文件读取不会触发任何 Minecraft 区块加载、
 * 联网上传或自动 Seed 求解。世界指纹仅用于拒绝跨世界证据混合。
 */
public record SolverResearchInput(
        Map<String, RandomSpreadSpec> models,
        List<StartPlacementConstraint> constraints,
        CandidateSeedSearch.SearchWindow searchWindow
) {
    public SolverResearchInput {
        models = Map.copyOf(models);
        constraints = List.copyOf(constraints);
        Objects.requireNonNull(searchWindow);
    }

    public static SolverResearchInput load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file) || Files.isSymbolicLink(file)
                || Files.size(file) > 65_536) {
            throw new IOException("solver input is missing, symlinked or exceeds 64 KiB");
        }
        Properties p = new Properties();
        try (InputStream stream = Files.newInputStream(file)) {
            p.load(stream);
        }

        String world = required(p, "worldFingerprint");
        String version = required(p, "gameVersion");
        String dimension = required(p, "dimension");

        int modelCount = integer(p, "models.count");
        int constraintCount = integer(p, "constraints.count");
        if (modelCount < 1 || modelCount > 32 || constraintCount < 2
                || constraintCount > 128) {
            throw new IllegalArgumentException("models/constraints size outside limits");
        }
        Map<String, RandomSpreadSpec> models = new HashMap<>();
        for (int i = 0; i < modelCount; i++) {
            String prefix = "model." + i + ".";
            RandomSpreadSpec spec = new RandomSpreadSpec(
                    required(p, prefix + "id"),
                    integer(p, prefix + "spacing"),
                    integer(p, prefix + "separation"),
                    RandomSpreadSpec.Distribution.valueOf(required(p, prefix + "distribution")),
                    integer(p, prefix + "salt"));
            if (models.putIfAbsent(spec.structureSetId(), spec) != null) {
                throw new IllegalArgumentException("duplicate structure set parameters");
            }
        }

        List<StartPlacementConstraint> constraints = new ArrayList<>();
        for (int i = 0; i < constraintCount; i++) {
            String prefix = "constraint." + i + ".";
            String setId = required(p, prefix + "structureSet");
            StartPlacementConstraint.Origin origin =
                    StartPlacementConstraint.Origin.valueOf(required(p, prefix + "origin"));
            StartPlacementConstraint.Split split =
                    StartPlacementConstraint.Split.valueOf(required(p, prefix + "split"));
            String geometry = prefix + "geometry.";
            boolean hasGeometryFields = p.stringPropertyNames().stream()
                    .anyMatch(key -> key.startsWith(geometry));
            if (!hasGeometryFields) {
                // P2.7/P2.8 旧格式保持原样，单矩形仍是唯一有效起点范围。
                constraints.add(new StartPlacementConstraint(
                        world, version, dimension, setId,
                        new ChunkBounds(integer(p, prefix + "minChunkX"),
                                integer(p, prefix + "minChunkZ"),
                                integer(p, prefix + "maxChunkX"),
                                integer(p, prefix + "maxChunkZ")),
                        origin, split));
                continue;
            }
            if (origin != StartPlacementConstraint.Origin.GEOMETRY_ESTIMATE
                    || p.stringPropertyNames().stream().anyMatch(key ->
                        key.startsWith(prefix + "minChunk") || key.startsWith(prefix + "maxChunk"))) {
                throw new IllegalArgumentException(
                        "geometry evidence cannot masquerade as controlled exact starts "
                        + "or mix with manually entered chunk bounds");
            }
            var observed = new StructureFootprintInverter.ObservedFootprint(
                    world, version, dimension, setId, required(p, geometry + "structureId"),
                    new StructureFootprintInverter.BlockBounds(
                            integer(p, geometry + "minBlockX"),
                            integer(p, geometry + "minBlockZ"),
                            integer(p, geometry + "maxBlockX"),
                            integer(p, geometry + "maxBlockZ")),
                    StructureFootprintInverter.FootprintQuality.valueOf(
                            required(p, geometry + "quality")));
            int calibrationCount = integer(p, geometry + "calibrations.count");
            if (calibrationCount < 1 || calibrationCount > 8) {
                throw new IllegalArgumentException("geometry calibration count must be 1..8");
            }
            List<StructureFootprintInverter.Calibration> calibrations = new ArrayList<>();
            for (int j = 0; j < calibrationCount; j++) {
                String c = geometry + "calibration." + j + ".";
                if (!"CONTROLLED_TRUE_STRUCTURE_START".equals(required(p, c + "referenceKind"))) {
                    throw new IllegalArgumentException(
                            "structure geometry calibration needs an independently checked control start");
                }
                calibrations.add(StructureFootprintInverter.Calibration.fromControlled(
                        required(p, c + "referenceId"),
                        required(p, c + "gameVersion"),
                        required(p, c + "dimension"),
                        required(p, c + "structureSet"),
                        required(p, c + "structureId"),
                        required(p, c + "variant"),
                        new StructureFootprintInverter.BlockBounds(
                                integer(p, c + "control.minBlockX"),
                                integer(p, c + "control.minBlockZ"),
                                integer(p, c + "control.maxBlockX"),
                                integer(p, c + "control.maxBlockZ")),
                        integer(p, c + "control.trueStartChunkX"),
                        integer(p, c + "control.trueStartChunkZ"),
                        integer(p, c + "toleranceBlocks")));
            }
            var inferred = StructureFootprintInverter.infer(observed, calibrations);
            constraints.add(inferred.toConstraint(observed, split));
        }

        CandidateSeedSearch.SearchWindow window = new CandidateSeedSearch.SearchWindow(
                longValue(p, "search.startLow48"),
                longValue(p, "search.endExclusive"),
                integer(p, "search.maxEvaluations"),
                integer(p, "search.maxResults"),
                longValue(p, "search.timeoutMillis")
        );
        return new SolverResearchInput(models, constraints, window);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException("missing or invalid solver field: " + key);
        }
        return value.trim();
    }

    private static int integer(Properties properties, String key) {
        return Integer.parseInt(required(properties, key));
    }

    private static long longValue(Properties properties, String key) {
        return Long.parseLong(required(properties, key));
    }
}
