package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.generation.DimensionProfile;
import io.github.fxjcangku.worldseedlab.core.generation.GenerationDiagnostics;
import io.github.fxjcangku.worldseedlab.core.generation.GenerationJsonLines;
import io.github.fxjcangku.worldseedlab.core.generation.ResearchReportStore;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkKey;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkSample;
import io.github.fxjcangku.worldseedlab.core.generation.WorldgenReport;
import io.github.fxjcangku.worldseedlab.core.probe.CompletedChunkStore;
import io.github.fxjcangku.worldseedlab.core.probe.AsyncEvidenceWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * P2 零外部依赖正反例：
 * 维度信息、数据隔离、地形统计、日志内容和原版/自定义命名空间。
 * 不在测试中访问真实多人服务器或推测世界 Seed。
 */
public final class P2GenerationSmokeTest {
    private P2GenerationSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        String worldA = "a".repeat(64);
        String worldB = "b".repeat(64);
        Instant now = Instant.parse("2026-10-14T00:00:00Z");
        DimensionProfile profile = new DimensionProfile(
                worldA, "deeper_dark:deep_realm", "26.2", -64, 320, 63,
                true, false, null, now);
        expect(profile.totalHeight() == 384, "dimension build height");
        expect(profile.ultraWarm() == null, "unverified dimension attribute is unknown");
        String dimensionLine = GenerationJsonLines.profile(profile);
        expect(dimensionLine.contains("\"ultra_warm\":null"), "unknown value must be JSON null");
        expect(dimensionLine.contains("deeper_dark:deep_realm"), "custom dimension ID missing");

        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < 16; i++) heights.add(64 + i);
        TerrainChunkSample sample = new TerrainChunkSample(
                worldA, profile.dimension(), profile.clientVersion(), -3, 5,
                -64, 320, 63, heights, now);
        expect(sample.minSurfaceY() == 64, "minimum height");
        expect(sample.maxSurfaceY() == 79, "maximum height");
        expect(sample.relief() == 15, "terrain relief");
        expect(Math.abs(sample.meanSurfaceY() - 71.5) < .01, "mean height");
        expect(GenerationJsonLines.terrain(sample).contains("\"chunk_x\":-3"),
                "terrain chunk location");
        expect(GenerationJsonLines.terrain(sample).contains("\"surface_y\":[64,65,66"),
                "heightmap JSON");

        // 必须做值语义拷贝，防止后台持久化读到主线程后来改变的采样数组。
        heights.set(0, 200);
        expect(sample.surfaceHeights().get(0) == 64, "immutable captured heights");
        expectFail(() -> new TerrainChunkSample(worldA, profile.dimension(), "26.2",
                0, 0, -64, 320, 63, List.of(10, 20), now), "reject partial terrain sample");
        expectFail(() -> new DimensionProfile(worldA, "invalid-dimension", "26.2",
                -64, 320, 63, true, false, null, now), "reject non namespaced dimension");

        GenerationDiagnostics diagnostics = new GenerationDiagnostics(profile);
        diagnostics.observe(sample);
        var summary = diagnostics.snapshot();
        expect(summary.terrainChunks() == 1, "terrain statistics");
        expect(summary.maximumChunkRelief() == 15, "maximum per-chunk relief");
        expectFail(() -> diagnostics.observe(new TerrainChunkSample(
                worldB, profile.dimension(), "26.2", 1, 2, -64, 320, 63,
                sample.surfaceHeights(), now)), "block cross-world metrics");

        Path folder = Files.createTempDirectory("wsl-p2-");
        try {
            CompletedChunkStore store = new CompletedChunkStore(folder);
            store.appendDimensionProfile(profile);
            store.appendTerrain(sample);
            var indexed = store.loadTerrainCompleted(worldA, profile.dimension());
            expect(indexed.contains(new TerrainChunkKey(-3, 5)), "terrain resume index");
            expect(indexed.size() == 1, "no duplicate index entries");
            expect(store.loadCompleted(worldA, profile.dimension()).isEmpty(),
                    "P2 terrain must not forge P1 completed quart samples");
            expect(store.loadTerrainCompleted(worldA, "minecraft:overworld").isEmpty(),
                    "dimension separation");
            expect(store.loadTerrainCompleted(worldB, profile.dimension()).isEmpty(),
                    "world separation");
            var key = TerrainChunkKey.parse("-3,5");
            expect(key.asLine().equals("-3,5"), "terrain journal round trip");
            expectFail(() -> TerrainChunkKey.parse("-3,5,7"), "invalid journal entry");

            String report = WorldgenReport.render(profile, summary,
                    Map.of("minecraft:plains", 128L, "deeper_dark:azure_sanctuary", 12L),
                    140, 140, 1, 0, false);
            expect(report.contains("研究结论: UNKNOWN"), "P2 never confirms seed");
            expect(report.contains("完整世界 Seed: 未读取 / 未求解 / 未验证"),
                    "P2 cannot claim recovery");
            expect(report.contains("非 minecraft 命名空间的已观察群系种类: 1"),
                    "custom namespace disclosure");
            expect(!report.contains("192.168.") && !report.contains("server.example.com"),
                    "report must contain no supplied host");
            // 验证队列按顺序写入：正常退出可以等待落盘，不冒充无限持久化保证。
            Path asynchronousDir = folder.resolve("asynchronous");
            AsyncEvidenceWriter backgroundWriter = new AsyncEvidenceWriter(
                    new CompletedChunkStore(asynchronousDir), worldA, profile.dimension());
            expect(backgroundWriter.offerDimension(profile), "enqueue dimension profile");
            expect(backgroundWriter.offerTerrain(sample), "enqueue terrain profile");
            expect(backgroundWriter.closeAndAwait(3000), "writer should drain accepted data");
            expect(backgroundWriter.writtenTerrainChunks() == 1, "terrain async counter");
            expect(new CompletedChunkStore(asynchronousDir)
                    .loadTerrainCompleted(worldA, profile.dimension())
                    .contains(new TerrainChunkKey(-3, 5)), "async journal persisted");

            Path reportFile = new ResearchReportStore(folder).export(profile, report);
            expect(Files.readString(reportFile, StandardCharsets.UTF_8).equals(report),
                    "round-trip human-readable report");
        } finally {
            try (var walk = Files.walk(folder)) {
                for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        System.out.println("WorldSeedLab P2 generation diagnostics smoke checks passed.");
    }

    private static void expect(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }

    private static void expectFail(Runnable action, String label) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(label);
    }
}
