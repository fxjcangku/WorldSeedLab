package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceKind;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceOrigin;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkSample;
import io.github.fxjcangku.worldseedlab.core.map.MapLayer;
import io.github.fxjcangku.worldseedlab.core.map.MapPalette;
import io.github.fxjcangku.worldseedlab.core.map.MapQuery;
import io.github.fxjcangku.worldseedlab.core.map.MapView;
import io.github.fxjcangku.worldseedlab.core.map.MapTileArchive;
import io.github.fxjcangku.worldseedlab.core.map.ObservedMapRepository;
import io.github.fxjcangku.worldseedlab.core.map.ObservedMapLoaderRegression;
import io.github.fxjcangku.worldseedlab.core.probe.ChunkKey;
import io.github.fxjcangku.worldseedlab.core.probe.CompletedChunkStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 已探索地图测试：跨重启/跨维度、负坐标、三层模式和未知区域。
 * 只用真实记录模型的合成位置验证地图逻辑，绝不虚称已破解 Seed。
 */
public final class P25MapSmokeTest {
    private P25MapSmokeTest() {}

    public static void main(String[] args) throws Exception {
        String world = "a".repeat(64), otherWorld = "b".repeat(64);
        String dim = "minecraft:overworld";
        Instant time = Instant.parse("2026-10-14T00:00:00Z");
        ChunkKey chunk = new ChunkKey(-2, 3, 8);
        var observed = new EvidenceRecord(UUID.randomUUID(), world, dim, "26.2",
                EvidenceKind.BIOME_OBSERVATION, "deeper_dark:azure_sanctuary",
                -30, 66, 50, EvidenceOrigin.CLIENT_OBSERVATION, time, "real observation");
        var marked = new EvidenceRecord(UUID.randomUUID(), world, dim, "26.2",
                EvidenceKind.STRUCTURE_OBSERVATION, "minecraft:shipwreck",
                -29, 65, 51, EvidenceOrigin.MANUAL_MARK, time, "unverified sighting");

        Path root = Files.createTempDirectory("worldseedlab-map-test");
        try {
            CompletedChunkStore store = new CompletedChunkStore(root);
            store.appendChunk(world, dim, chunk, List.of(observed));
            store.appendTerrain(new TerrainChunkSample(world, dim, "26.2",
                    -2, 3, -64, 320, 63, java.util.Collections.nCopies(16, 67), time));
            store.appendManual(marked);

            // 模拟完全重新启动客户端：新对象通过地图索引加载已持久化数据。
            ObservedMapRepository viewer = new ObservedMapRepository(store.scopedFolder(world, dim));
            var surface = viewer.query(new MapQuery(-30, 50, 40, 28, MapLayer.SURFACE_BIOME, 200));
            check(surface.cells().size() == 1, "surface must use observed height and biome");
            check(surface.cells().get(0).biomeId().equals("deeper_dark:azure_sanctuary"),
                    "modded biome ID is preserved, not replaced by vanilla map prediction");
            check(surface.cells().get(0).observedY() == 66, "actual sample Y retained");
            check(surface.markers().size() == 1, "manual sighting recovered after restart");
            check(surface.markers().get(0).structureId().equals("minecraft:shipwreck"),
                    "manual structure marker type");
            check(surface.message().contains("未知区域"), "unknown must remain unknown");

            var slice = viewer.query(new MapQuery(-30, 50, 40, 28, MapLayer.ALTITUDE_BIOME, 70));
            check(slice.cells().size() == 1, "slice should select nearby actually sampled Y");
            var absentSlice = viewer.query(new MapQuery(-30, 50, 40, 28,
                    MapLayer.ALTITUDE_BIOME, 200));
            check(absentSlice.cells().isEmpty(), "unobserved altitude may not be filled");
            var height = viewer.query(new MapQuery(-30, 50, 40, 28, MapLayer.TERRAIN_HEIGHT, 66));
            check(height.cells().size() == 16, "4x4 quart centers per actual height tile");
            check(height.cells().stream().allMatch(cell -> cell.surfaceY() == 67),
                    "stored height values preserved");

            var unrelated = new ObservedMapRepository(store.scopedFolder(otherWorld, dim))
                    .query(new MapQuery(-30, 50, 40, 28, MapLayer.SURFACE_BIOME, 66));
            check(unrelated.cells().isEmpty(), "different world scope must remain separate");
            var nether = new ObservedMapRepository(store.scopedFolder(world, "minecraft:the_nether"))
                    .query(new MapQuery(-30, 50, 40, 28, MapLayer.SURFACE_BIOME, 66));
            check(nether.cells().isEmpty(), "different dimensions must remain separate");
            var distant = viewer.query(new MapQuery(8000, 8000, 40, 28,
                    MapLayer.TERRAIN_HEIGHT, 66));
            check(distant.cells().isEmpty(), "unexplored locations are never fabricated");
            check(MapPalette.biome("deeper_dark:azure_sanctuary")
                            == MapPalette.biome("deeper_dark:azure_sanctuary"),
                    "custom biome display color must be stable");
            check(MapPalette.biome("minecraft:ocean") != MapPalette.biome("minecraft:desert"),
                    "ordinary biome palette categories differ");

            expectFail(() -> new MapQuery(0, 0, 9000, 1,
                    MapLayer.ALTITUDE_BIOME, 60), "unbounded viewport must fail");
            expectFail(() -> new MapQuery(0, 0, 20, 20,
                    MapLayer.ALTITUDE_BIOME, 9000), "invalid Y must fail");

            // 坏瓦片必须拒绝；相邻通过校验的瓦片仍可显示，同时显式标记不完整。
            String damagedWorld = "c".repeat(64);
            Path damagedScope = store.scopedFolder(damagedWorld, dim);
            store.appendChunk(damagedWorld, dim, new ChunkKey(-1, 3, 8), List.of(
                    new EvidenceRecord(UUID.randomUUID(), damagedWorld, dim, "26.2",
                            EvidenceKind.BIOME_OBSERVATION, "minecraft:plains",
                            -14, 66, 50, EvidenceOrigin.CLIENT_OBSERVATION, time, "controlled test")));
            Path damagedTile = MapTileArchive.root(damagedScope)
                    .resolve("biomes/r.-1.0/b.-2.3.8.wsl.gz");
            Files.createDirectories(damagedTile.getParent());
            Files.writeString(damagedTile, "not a gzip encoded biome observation");
            MapView damaged = new ObservedMapRepository(damagedScope).query(
                    new MapQuery(-30, 50, 40, 28, MapLayer.ALTITUDE_BIOME, 66));
            check(damaged.incomplete() && damaged.message().contains("部分地图索引读取失败"),
                    "corrupt tile must be explicitly reported");
            check(damaged.cells().size() == 1 && damaged.cells().get(0).quartX() == -4,
                    "corrupt tile remains unknown while validated neighbor survives");

            // 旧完成索引会阻止重扫；不能再提示用户重复跑图或删除历史证据。
            Path legacy = store.scopedFolder(world, "minecraft:the_end");
            Files.createDirectories(legacy);
            Files.writeString(legacy.resolve("evidence.jsonl"), "legacy");
            MapView old = new ObservedMapRepository(legacy).query(
                    new MapQuery(0, 0, 16, 12, MapLayer.ALTITUDE_BIOME, 64));
            check(old.message().contains("旧版证据"), "legacy data must not silently vanish");
            check(old.message().contains("不会重扫"), "legacy hint must describe completed-index behavior");
            check(Files.readString(legacy.resolve("evidence.jsonl")).equals("legacy"),
                    "map query must not rewrite legacy evidence");

            checkMissingLayersAndSurfaceY(store, time);
            ObservedMapLoaderRegression.run();
            io.github.fxjcangku.worldseedlab.core.map.SurfaceMapRegression.run();
        } finally {
            try (var walk = Files.walk(root)) {
                for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        System.out.println("WorldSeedLab P2.5 observed map smoke checks passed.");
    }

    /** 缺失图层不补假数据；表面高度使用已验证的两版 ChunkAccess.getHeight 语义。 */
    private static void checkMissingLayersAndSurfaceY(CompletedChunkStore store, Instant time)
            throws Exception {
        String world = "d".repeat(64), dim = "minecraft:overworld";
        var low = new EvidenceRecord(UUID.randomUUID(), world, dim, "26.2",
                EvidenceKind.BIOME_OBSERVATION, "minecraft:dripstone_caves",
                2, 62, 2, EvidenceOrigin.CLIENT_OBSERVATION, time, "controlled test");
        var high = new EvidenceRecord(UUID.randomUUID(), world, dim, "26.2",
                EvidenceKind.BIOME_OBSERVATION, "minecraft:plains",
                2, 66, 2, EvidenceOrigin.CLIENT_OBSERVATION, time, "controlled test");
        store.appendChunk(world, dim, new ChunkKey(0, 0, 4), List.of(low, high));
        var viewer = new ObservedMapRepository(store.scopedFolder(world, dim));
        MapQuery query = new MapQuery(8, 8, 4, 4, MapLayer.SURFACE_BIOME, 66);
        MapView noHeight = viewer.query(query);
        check(noHeight.cells().isEmpty() && noHeight.message().contains("缺少地表高度"),
                "biome-only evidence may not invent surface heights");
        check(viewer.query(query.withLayer(MapLayer.ALTITUDE_BIOME)).cells().size() == 1,
                "biome-only evidence remains accessible through altitude layer");
        MapView wrongY = viewer.query(query.withLayer(MapLayer.ALTITUDE_BIOME).withY(200));
        check(wrongY.cells().isEmpty() && wrongY.message().contains("目标高度"),
                "wrong altitude must explain why observed columns remain blank");

        store.appendTerrain(new TerrainChunkSample(world, dim, "26.2", 0, 0,
                -64, 320, 63, java.util.Collections.nCopies(16, 65), time));
        MapView surface = viewer.query(query);
        check(surface.cells().size() == 1 && surface.cells().get(0).observedY() == 66,
                "stored top-block Y must not be decremented a second time");
        check(surface.cells().get(0).biomeId().equals("minecraft:plains"),
                "surface near quart boundary must use the nearest observed biome");

        // 同样存在高度瓦片，但从未采群系的相邻区块只能画高度层。
        store.appendTerrain(new TerrainChunkSample(world, dim, "26.2", 1, 0,
                -64, 320, 63, java.util.Collections.nCopies(16, 70), time));
        MapView noBiomes = viewer.query(query.moved(16, 0));
        check(noBiomes.cells().isEmpty() && noBiomes.message().contains("地形高度"),
                "height-only evidence must direct users to the real available layer");
        check(viewer.query(query.moved(16, 0).withLayer(MapLayer.TERRAIN_HEIGHT)).cells().size() == 16,
                "height-only cells remain available without guessed biomes");

        Path tileRoot = MapTileArchive.root(store.scopedFolder(world, dim));
        corruptPayloadInteger(tileRoot.resolve("biomes/r.0.0/b.0.0.4.wsl.gz"), 27, Integer.MIN_VALUE);
        MapView invalidY = viewer.query(query.withLayer(MapLayer.ALTITUDE_BIOME));
        check(invalidY.incomplete() && invalidY.cells().isEmpty(),
                "corrupted sample Y cannot overflow distance and become a visible biome");
        corruptPayloadInteger(tileRoot.resolve("heights/r.0.0/h.0.0.wsl.gz"), 17, Integer.MAX_VALUE);
        MapView invalidHeight = viewer.query(query.withLayer(MapLayer.TERRAIN_HEIGHT));
        check(invalidHeight.incomplete() && invalidHeight.cells().isEmpty(),
                "corrupted height cannot be rendered as a genuine mountain");
    }

    /** 在合法瓦片中仅破坏一个整数，确保校验不只是识别 gzip 文件头失败。 */
    private static void corruptPayloadInteger(Path file, int offset, int replacement) throws Exception {
        byte[] payload;
        try (var in = new java.util.zip.GZIPInputStream(Files.newInputStream(file))) {
            payload = in.readAllBytes();
        }
        java.nio.ByteBuffer.wrap(payload).putInt(offset, replacement);
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(payload);
        }
    }

    private static void check(boolean okay, String message) {
        if (!okay) throw new AssertionError(message);
    }
    private static void expectFail(Runnable op, String msg) {
        try { op.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(msg);
    }
}
