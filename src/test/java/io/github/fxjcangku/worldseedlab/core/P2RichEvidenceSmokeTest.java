package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.generation.RichChunkJsonLines;
import io.github.fxjcangku.worldseedlab.core.generation.RichChunkSample;
import io.github.fxjcangku.worldseedlab.core.generation.RichObservationStats;
import io.github.fxjcangku.worldseedlab.core.generation.RichReportAppendix;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkKey;
import io.github.fxjcangku.worldseedlab.core.probe.AsyncEvidenceWriter;
import io.github.fxjcangku.worldseedlab.core.probe.CompletedChunkStore;
import io.github.fxjcangku.worldseedlab.core.probe.ProbeConfigFile;
import io.github.fxjcangku.worldseedlab.core.probe.ProbeSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * P2 精细取证离线自检：数据合法性、空间字段、跨世界隔离、索引及异步写入。
 * 不访问多人服务器、不读取真实世界 Seed、无需 Minecraft 类。
 */
public final class P2RichEvidenceSmokeTest {
    private P2RichEvidenceSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        String world = "a".repeat(64);
        String other = "b".repeat(64);
        List<Integer> surface = new ArrayList<>();
        List<Integer> seabed = new ArrayList<>();
        List<Integer> motion = new ArrayList<>();
        List<String> tops = new ArrayList<>();
        List<String> floors = new ArrayList<>();
        List<String> biomes = new ArrayList<>();
        List<String> fluids = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            surface.add(76 + index);
            seabed.add(46 + index);
            motion.add(70 + index);
            tops.add(index % 2 == 0 ? "minecraft:grass_block" : "deeper_dark:crystal_block");
            floors.add("minecraft:stone");
            biomes.add(index % 2 == 0 ? "minecraft:plains" : "deeper_dark:crystal_cavern");
            fluids.add(index < 4 ? "minecraft:water" : "minecraft:empty");
        }
        List<RichChunkSample.DeepLayer> layers = new ArrayList<>();
        for (int x : new int[] {2, 10}) {
            for (int z : new int[] {2, 10}) {
                for (int y : new int[] {60, 40, 20, -20}) {
                    layers.add(new RichChunkSample.DeepLayer(x, z, y,
                            y == 20 ? "minecraft:cave_air" : "minecraft:stone"));
                }
            }
        }
        RichChunkSample sample = new RichChunkSample(world, "minecraft:overworld", "26.2",
                -12, 34, -64, 320, surface, seabed, motion, tops, floors,
                biomes, fluids, layers, Instant.parse("2026-10-14T00:00:00Z"));
        require(sample.fluidColumns() == 4, "fluid count");
        require(sample.highestSurface() == 91, "height summary");
        require(sample.deepestOceanFloor() == 46, "seabed summary");
        String json = RichChunkJsonLines.serialize(sample);
        require(json.contains("\"schema\":3"), "schema V3");
        require(json.contains("\"chunk_x\":-12"), "chunk x");
        require(json.contains("\"ocean_floor_y\":[46,47,48"), "ocean floor schema");
        require(json.contains("\"deep_layers\""), "subsurface field");
        require(json.contains("\"observation_only\":true"), "never claim worldgen origin");
        require(!json.contains("identity.key"), "do not leak private material");

        tops.set(0, "minecraft:diamond_block");
        require(sample.surfaceBlockIds().get(0).equals("minecraft:grass_block"),
                "immutable deep-copy of source list");
        expectFail(() -> new RichChunkSample(world, "minecraft:overworld", "26.2",
                0, 0, -64, 320, List.of(1), seabed, motion, tops, floors,
                biomes, fluids, layers, Instant.now()), "bad column count rejected");
        expectFail(() -> new RichChunkSample(world, "minecraft:overworld", "26.2",
                0, 0, -64, 320, surface, seabed, motion,
                List.of("INVALID-NAMESPACE"), floors, biomes, fluids, layers, Instant.now()),
                "invalid ID rejected");
        RichObservationStats stats = new RichObservationStats(world, "minecraft:overworld");
        stats.observe(sample);
        var snapshot = stats.snapshot();
        require(snapshot.observedChunks() == 1, "one observed rich chunk");
        require(snapshot.fluidColumns() == 4, "stats: fluid");
        require(snapshot.undergroundAirObservations() == 4, "stats: cave air");
        expectFail(() -> new RichObservationStats(other, "minecraft:overworld").observe(sample),
                "cross-world observation must fail");
        String report = RichReportAppendix.render(true, 1, snapshot);
        require(report.contains("地表方块") && report.contains("deeper_dark:crystal_block"),
                "human readable classification");
        require(!report.contains("chunk_x") && !report.contains("identity.key"),
                "summary must not expose coordinates or local keys");

        require(!ProbeSettings.DEFAULT.richEvidence(), "detailed capture disabled by default");
        var enabled = ProbeSettings.DEFAULT.withRichEvidence(true);
        require(enabled.withRadius(6).richEvidence(), "radius preserves enabled setting");
        require(enabled.nextVerticalStride().richEvidence(), "stride preserves enabled setting");
        require(enabled.nextBudget().richEvidence(), "budget preserves enabled setting");
        require(!enabled.withRichEvidence(false).richEvidence(), "toggle can disarm");

        Path root = Files.createTempDirectory("wsl-rich-test-");
        try {
            var preferences = new ProbeConfigFile(root);
            preferences.save(enabled);
            require(preferences.load().equals(enabled), "rich preference persistence");

            CompletedChunkStore store = new CompletedChunkStore(root);
            store.appendRich(sample);
            TerrainChunkKey key = new TerrainChunkKey(-12, 34);
            require(store.loadRichCompleted(world, "minecraft:overworld").contains(key),
                    "rich completion journal");
            require(store.loadTerrainCompleted(world, "minecraft:overworld").isEmpty(),
                    "rich journal must not complete P2 heightmap records");
            require(store.loadCompleted(world, "minecraft:overworld").isEmpty(),
                    "rich journal must not complete P1 biome records");
            require(store.loadRichCompleted(world, "minecraft:the_nether").isEmpty(),
                    "cross-dimensional journal isolation");
            require(store.loadRichCompleted(other, "minecraft:overworld").isEmpty(),
                    "cross-world journal isolation");

            Path dataFile = store.dataPath(world, "minecraft:overworld")
                    .getParent().resolve("rich-chunks.jsonl");
            require(Files.readAllLines(dataFile, StandardCharsets.UTF_8).size() == 1,
                    "one JSONL record per chunk");

            Path asyncRoot = root.resolve("async");
            var writer = new AsyncEvidenceWriter(new CompletedChunkStore(asyncRoot),
                    world, "minecraft:overworld");
            require(writer.offerRich(sample), "async queue accepted the sample");
            require(writer.closeAndAwait(3000), "writer drains before exit");
            require(writer.writtenRichChunks() == 1, "writer success counter");
            require(new CompletedChunkStore(asyncRoot)
                    .loadRichCompleted(world, "minecraft:overworld").contains(key),
                    "async index written");
        } finally {
            try (var stream = Files.walk(root)) {
                for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(item);
                }
            }
        }
        System.out.println("WorldSeedLab P2 rich evidence smoke checks passed.");
    }

    private static void expectFail(Runnable operation, String message) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
