package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.generation.DetailObservationStats;
import io.github.fxjcangku.worldseedlab.core.generation.DetailReportAppendix;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainDetailJsonLines;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainDetailKey;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainDetailSample;
import io.github.fxjcangku.worldseedlab.core.probe.AsyncEvidenceWriter;
import io.github.fxjcangku.worldseedlab.core.probe.CompletedChunkStore;
import io.github.fxjcangku.worldseedlab.core.probe.DetailMode;
import io.github.fxjcangku.worldseedlab.core.probe.ProbeConfigFile;
import io.github.fxjcangku.worldseedlab.core.probe.ProbeSettings;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** P2.1 扩展证据的无依赖正反例：两版本均通过 Gradle 执行。 */
public final class P2DetailSmokeTest {
    private P2DetailSmokeTest() { }

    public static void main(String[] args) throws Exception {
        String a = "a".repeat(64), b = "b".repeat(64);
        expect(ProbeSettings.DEFAULT.detailMode() == DetailMode.OFF, "default must be disabled");
        ProbeSettings config = ProbeSettings.DEFAULT.nextDetailMode();
        expect(config.detailMode() == DetailMode.SURFACE, "surface enabled by explicit cycle");
        expect(config.nextDetailMode().detailMode() == DetailMode.STRATA, "opt-in strata");
        expect(config.nextDetailMode().nextDetailMode().detailMode() == DetailMode.OFF,
                "turn off cycles to disabled");
        expect(config.withRadius(3).detailMode() == DetailMode.SURFACE,
                "changing radius must preserve detail profile");

        var surface = new ArrayList<TerrainDetailSample.SurfaceCell>();
        for (int x = 2; x < 16; x += 4) {
            for (int z = 2; z < 16; z += 4) {
                surface.add(new TerrainDetailSample.SurfaceCell(
                        x, z, 64, "minecraft:grass_block",
                        "Block{minecraft:grass_block}[snowy=false]", "minecraft:empty",
                        z == 2 ? "minecraft:plains" : "deeper_dark:azure_sanctuary",
                        65, 52, 62, "minecraft:stone"));
            }
        }
        var strata = new ArrayList<TerrainDetailSample.VerticalCell>();
        for (int x : new int[] {2, 10}) {
            for (int z : new int[] {2, 10}) {
                strata.add(new TerrainDetailSample.VerticalCell(
                        x, z, -45, "minecraft:stone", "minecraft:empty", "minecraft:dripstone_caves"));
            }
        }
        Instant now = Instant.parse("2026-10-14T00:00:00Z");
        var surfaceSample = new TerrainDetailSample(a, "minecraft:overworld", "26.2",
                -1, 5, DetailMode.SURFACE, 0, surface, List.of(), now);
        var strataSample = new TerrainDetailSample(a, "minecraft:overworld", "26.2",
                -1, 5, DetailMode.STRATA, 16, surface, strata, now);
        String json = TerrainDetailJsonLines.serialize(strataSample);
        expect(json.contains("\"type\":\"terrain_detail\""), "typed evidence");
        expect(json.contains("\"mode\":\"STRATA\""), "explicit depth profile");
        expect(json.contains("\"schema\":4"), "heightmap enriched schema");
        expect(json.contains("\"ocean_floor_y\":52"), "ocean floor map");
        expect(json.contains("\"motion_blocking_y\":62"), "motion blocking map");
        expect(json.contains("\"ocean_floor_block\":\"minecraft:stone\""),
                "seabed block registry ID");
        expect(json.contains("\"generation_origin_verified\":false"), "must not claim generation");
        expect(json.contains("deeper_dark:azure_sanctuary"), "actual biome ID");
        expect(json.contains("\"vertical_stride_blocks\":16"), "depth stride saved");
        expect(json.indexOf('\n') < 0, "JSONL must remain one line");
        var statistics = new DetailObservationStats(a, "minecraft:overworld");
        statistics.observe(surfaceSample);
        statistics.observe(strataSample);
        var summary = statistics.snapshot();
        expect(summary.acceptedChunks() == 2, "detail acceptance count");
        expect(summary.verticalCells() == strata.size(), "strata cells counted once");
        expect(summary.oceanFloorBlocks().get("minecraft:stone") == 32,
                "seabed categories include both surface and strata samples");
        expect(DetailReportAppendix.render(summary).contains("地表材质"),
                "human readable summary");
        expect(!DetailReportAppendix.render(summary).contains("\"chunk_x\":"),
                "anonymized summary never exposes chunk coordinates");
        expectFail(() -> new DetailObservationStats(b, "minecraft:overworld")
                .observe(strataSample), "detailed statistics must reject foreign world scope");

        var mutable = new ArrayList<>(surface);
        var immutable = new TerrainDetailSample(a, "minecraft:overworld", "26.2",
                0, 1, DetailMode.SURFACE, 0, mutable, List.of(), now);
        mutable.clear();
        expect(immutable.surface().size() == 16, "immutable snapshot");
        expectFail(() -> new TerrainDetailKey(0, 0, DetailMode.OFF),
                "OFF must not claim completed blocks");
        expectFail(() -> TerrainDetailKey.parse("1,2"), "strict detail index");
        expectFail(() -> new TerrainDetailSample(a, "minecraft:overworld", "26.2",
                0, 1, DetailMode.SURFACE, 0, List.of(), List.of(), now),
                "surface cannot be partially saved");

        Path folder = Files.createTempDirectory("wsl-detail-");
        try {
            ProbeConfigFile configFile = new ProbeConfigFile(folder);
            configFile.save(config);
            expect(configFile.load().detailMode() == DetailMode.SURFACE, "detail mode persists");

            var store = new CompletedChunkStore(folder);
            expect(store.loadDetailCompleted(a, "minecraft:overworld").isEmpty(), "new index empty");
            store.appendDetail(surfaceSample);
            expect(store.loadDetailCompleted(a, "minecraft:overworld")
                    .contains(new TerrainDetailKey(-1, 5, DetailMode.SURFACE)),
                    "surface completion persisted");
            expect(!store.loadDetailCompleted(a, "minecraft:overworld")
                    .contains(new TerrainDetailKey(-1, 5, DetailMode.STRATA)),
                    "surface mode must not complete strata mode");
            expect(store.loadDetailCompleted(b, "minecraft:overworld").isEmpty(),
                    "world isolation");
            expect(store.loadDetailCompleted(a, "minecraft:the_nether").isEmpty(),
                    "dimension isolation");
            var rawPath = store.dataPath(a, "minecraft:overworld")
                    .getParent().resolve("terrain-detail.jsonl");
            expect(Files.readAllLines(rawPath, StandardCharsets.UTF_8).size() == 1,
                    "exactly one persisted detail record");

            var writer = new AsyncEvidenceWriter(store, a, "minecraft:overworld");
            expect(writer.offerDetail(strataSample), "async strata acceptance");
            expect(!writer.offerDetail(new TerrainDetailSample(b, "minecraft:overworld", "26.2",
                    -1, 5, DetailMode.SURFACE, 0, surface, List.of(), now)),
                    "cross-world detailed evidence must fail closed");
            expect(writer.closeAndAwait(3000), "async flush");
            expect(writer.writtenDetailChunks() == 1, "persisted detailed chunk");
            expect(store.loadDetailCompleted(a, "minecraft:overworld")
                    .contains(new TerrainDetailKey(-1, 5, DetailMode.STRATA)),
                    "independent strata journal");
            expect(Files.readAllLines(rawPath, StandardCharsets.UTF_8).size() == 2,
                    "surface and strata records both survive");
            // 使用稀疏文件模拟接近 256 MiB 的已存在日志，不消耗同等内存。
            Path capacityRoot = folder.resolve("capacity-test");
            CompletedChunkStore capacityStore = new CompletedChunkStore(capacityRoot);
            Path oversized = capacityStore.dataPath(a, "minecraft:overworld")
                    .getParent().resolve("terrain-detail.jsonl");
            Files.createDirectories(oversized.getParent());
            try (FileChannel channel = FileChannel.open(oversized,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.position(256L * 1024 * 1024 - 32);
                channel.write(ByteBuffer.wrap(new byte[] {0}));
            }
            boolean rejected = false;
            try {
                capacityStore.appendDetail(surfaceSample);
            } catch (java.io.IOException expected) {
                rejected = expected.getMessage().contains("256 MiB");
            }
            expect(rejected, "detail log cannot silently exceed disk budget");
            expect(capacityStore.loadDetailCompleted(a, "minecraft:overworld").isEmpty(),
                    "capacity failure must not create completion journal");
        } finally {
            try (var walk = Files.walk(folder)) {
                for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(f);
                }
            }
        }
        System.out.println("WorldSeedLab P2 detail evidence smoke checks passed.");
    }

    private static void expect(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    private static void expectFail(Runnable action, String reason) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(reason);
    }
}
