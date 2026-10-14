package io.github.fxjcangku.worldseedlab.core.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 仅按屏幕窗口读取已经落盘的地图索引。地图取证与文件 I/O 不在渲染线程运行。
 *
 * <p>地表群系需要 BOTH：该列真实地表高度 + 最接近此高度的三维群系格点。
 * 缺少任一项则保持未知，不回退到原版 Seed 算出来的预测地形。
 */
public final class ObservedMapRepository {
    private static final int[] STRIDES = {4, 8, 16};
    private static final int MAX_Y_DISTANCE = 10;
    private final Path scopedFolder;

    public ObservedMapRepository(Path scopedFolder) {
        this.scopedFolder = scopedFolder.toAbsolutePath().normalize();
    }

    public MapView query(MapQuery query) throws IOException {
        Path root = MapTileArchive.root(scopedFolder);
        if (!Files.isDirectory(root)) {
            String hint = Files.isRegularFile(scopedFolder.resolve("evidence.jsonl"))
                    ? "存在旧版证据日志，但尚未建立地图索引；需重新跑图采样"
                    : "当前世界尚无已采集地图证据；请手动开启采集并正常跑图";
            return MapView.empty(query, hint);
        }

        Map<Long, Integer> heights = new HashMap<>();
        int loadedHeightChunks = 0;
        if (query.layer() != MapLayer.ALTITUDE_BIOME) {
            for (int cx = query.firstChunkX(); cx <= query.lastChunkX(); cx++) {
                for (int cz = query.firstChunkZ(); cz <= query.lastChunkZ(); cz++) {
                    int[] grid = MapTileArchive.readHeights(root, cx, cz);
                    if (grid == null) continue;
                    loadedHeightChunks++;
                    for (int x = 0; x < 4; x++) {
                        for (int z = 0; z < 4; z++) {
                            int qx = cx * 4 + x, qz = cz * 4 + z;
                            if (query.contains(qx, qz)) {
                                heights.put(pack(qx, qz), grid[x * 4 + z]);
                            }
                        }
                    }
                }
            }
        }

        List<MapView.Cell> cells;
        if (query.layer() == MapLayer.TERRAIN_HEIGHT) {
            cells = heights.entrySet().stream()
                    .map(e -> new MapView.Cell(first(e.getKey()), second(e.getKey()),
                            e.getValue(), null, e.getValue()))
                    .toList();
        } else {
            Map<Long, BiomeCandidate> best = new HashMap<>();
            for (int cx = query.firstChunkX(); cx <= query.lastChunkX(); cx++) {
                for (int cz = query.firstChunkZ(); cz <= query.lastChunkZ(); cz++) {
                    for (int stride : STRIDES) {
                        for (MapTileArchive.BiomePoint point :
                                MapTileArchive.readBiomes(root, cx, cz, stride)) {
                            int qx = Math.floorDiv(point.x(), 4);
                            int qz = Math.floorDiv(point.z(), 4);
                            if (!query.contains(qx, qz)) continue;
                            long id = pack(qx, qz);
                            Integer surface = heights.get(id);
                            if (query.layer() == MapLayer.SURFACE_BIOME && surface == null) {
                                continue;
                            }
                            int target = query.layer() == MapLayer.SURFACE_BIOME
                                    ? surface - 1 : query.targetY();
                            int distance = Math.abs(point.y() - target);
                            if (distance > MAX_Y_DISTANCE) continue;
                            BiomeCandidate old = best.get(id);
                            if (old == null || distance < old.distance()) {
                                best.put(id, new BiomeCandidate(distance,
                                        new MapView.Cell(qx, qz, point.y(),
                                                point.identifier(), surface == null ? 0 : surface)));
                            }
                        }
                    }
                }
            }
            cells = best.values().stream().map(BiomeCandidate::cell).toList();
        }

        List<MapView.Marker> markers = new ArrayList<>();
        for (MapView.Marker mark : MapTileArchive.readMarkers(root,
                query.firstChunkX(), query.firstChunkZ(),
                query.lastChunkX(), query.lastChunkZ())) {
            if (query.contains(Math.floorDiv(mark.x(), 4), Math.floorDiv(mark.z(), 4))) {
                markers.add(mark);
            }
        }

        int viewportArea = query.widthCells() * query.heightCells();
        String message = "本窗口有真实证据的格点 " + cells.size() + "/" + viewportArea
                + "；未知区域保持空白。地图不预测 Seed。";
        if (query.layer() == MapLayer.SURFACE_BIOME && loadedHeightChunks == 0) {
            message = "没有该窗口的地表高度证据；可切换 Y 高度层查看群系样本";
        }
        return new MapView(query, cells, markers, false, message);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static int first(long packed) { return (int) (packed >> 32); }
    private static int second(long packed) { return (int) packed; }

    private record BiomeCandidate(int distance, MapView.Cell cell) {}
}
