package io.github.fxjcangku.worldseedlab.core.solver;

import io.github.fxjcangku.worldseedlab.core.compatibility.StructureObservation.ChunkBounds;
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
            constraints.add(new StartPlacementConstraint(
                    world, version, dimension,
                    required(p, prefix + "structureSet"),
                    new ChunkBounds(integer(p, prefix + "minChunkX"),
                            integer(p, prefix + "minChunkZ"),
                            integer(p, prefix + "maxChunkX"),
                            integer(p, prefix + "maxChunkZ")),
                    StartPlacementConstraint.Origin.valueOf(required(p, prefix + "origin")),
                    StartPlacementConstraint.Split.valueOf(required(p, prefix + "split"))));
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
