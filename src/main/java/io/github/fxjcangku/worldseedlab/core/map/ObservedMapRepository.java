package io.github.fxjcangku.worldseedlab.core.map;

import java.io.IOException;
import java.io.InterruptedIOException;
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
        if (query.layer() == MapLayer.SURFACE_COLOR) return querySurface(root, query);
        if (!Files.isDirectory(root)) {
            String hint = Files.isRegularFile(scopedFolder.resolve("evidence.jsonl"))
                    ? "存在旧版证据但缺少地图索引；重复跑图不会重扫已完成区块，请保留旧证据等待离线索引迁移"
                    : "当前世界尚无已采集地图证据；请手动开启采集并正常跑图";
            return MapView.empty(query, hint);
        }

        Map<Long, Integer> heights = new HashMap<>();
        int loadedHeightChunks = 0;
        int unreadableParts = 0;
        if (query.layer() != MapLayer.ALTITUDE_BIOME) {
            for (int cx = query.firstChunkX(); cx <= query.lastChunkX(); cx++) {
                for (int cz = query.firstChunkZ(); cz <= query.lastChunkZ(); cz++) {
                    checkCancelled();
                    int[] grid;
                    try {
                        grid = MapTileArchive.readHeights(root, cx, cz);
                    } catch (IOException unreadable) {
                        // 单个坏瓦片保持未知；不让相邻已校验的真实证据整幅消失。
                        unreadableParts++;
                        continue;
                    }
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
        int observedBiomePoints = 0;
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
                        checkCancelled();
                        List<MapTileArchive.BiomePoint> points;
                        try {
                            points = MapTileArchive.readBiomes(root, cx, cz, stride);
                        } catch (IOException unreadable) {
                            unreadableParts++;
                            continue;
                        }
                        for (MapTileArchive.BiomePoint point : points) {
                            int qx = Math.floorDiv(point.x(), 4);
                            int qz = Math.floorDiv(point.z(), 4);
                            if (!query.contains(qx, qz)) continue;
                            observedBiomePoints++;
                            long id = pack(qx, qz);
                            Integer surface = heights.get(id);
                            if (query.layer() == MapLayer.SURFACE_BIOME && surface == null) {
                                continue;
                            }
                            // 两版 ChunkAccess.getHeight 已返回最高表面方块 Y，不能再次减一。
                            int target = query.layer() == MapLayer.SURFACE_BIOME
                                    ? surface : query.targetY();
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

        checkCancelled();
        List<MapView.Marker> markers = new ArrayList<>();
        try {
            for (MapView.Marker mark : MapTileArchive.readMarkers(root,
                    query.firstChunkX(), query.firstChunkZ(),
                    query.lastChunkX(), query.lastChunkZ())) {
                if (query.contains(Math.floorDiv(mark.x(), 4), Math.floorDiv(mark.z(), 4))) {
                    markers.add(mark);
                }
            }
        } catch (IOException unreadable) {
            // 标记索引失败不影响群系/高度瓦片；错误原因会随快照显示，不能伪装成完整地图。
            unreadableParts++;
        }

        int viewportArea = query.widthCells() * query.heightCells();
        String message = "本窗口有真实证据的格点 " + cells.size() + "/" + viewportArea
                + "；未知区域保持空白。地图不预测 Seed。";
        if (query.layer() == MapLayer.SURFACE_BIOME && loadedHeightChunks == 0) {
            message = observedBiomePoints > 0
                    ? "该窗口已有群系样本但缺少地表高度；切换“指定高度群系”查看，不推测地表"
                    : "当前视窗尚无已保存的地表证据；可回到已采集位置，或手动采集后刷新";
        } else if (cells.isEmpty()) {
            message = switch (query.layer()) {
                case SURFACE_COLOR -> throw new IllegalStateException("surface layer uses block observations");
                case SURFACE_BIOME -> "已保存地形高度，但缺少对应地表附近的群系；可切换“地形高度”查看";
                case ALTITUDE_BIOME -> observedBiomePoints > 0
                        ? "当前目标高度附近没有群系样本；请调整高度，未观测深度保持未知"
                        : "当前视窗没有已保存的群系样本；可回到已采集位置，或手动采集后刷新";
                case TERRAIN_HEIGHT -> "当前视窗没有已保存的地形高度；可回到已采集位置，或切换群系图层";
            };
        }
        if (unreadableParts > 0) {
            message = "部分地图索引读取失败（" + unreadableParts + " 项），相关区域保持未知；"
                    + "已保留其余真实证据，请备份并检查索引。" + message;
        }
        return new MapView(query, cells, markers, unreadableParts > 0, message);
    }

    /** 逐方块地表与 quart 群系分开查询；旧采样绝不插值成新的地表色。 */
    private MapView querySurface(Path root, MapQuery query) throws IOException {
        if (query.clientVersion().isEmpty()) {
            return MapView.empty(query, "逐方块地表需要当前游戏版本，拒绝混用其它版本的地图证据");
        }
        List<MapView.SurfaceCell> surface = new ArrayList<>();
        int unreadable = 0, loadedTiles = 0;
        int left = query.leftQuart() * 4, top = query.topQuart() * 4;
        int right = left + query.widthCells() * 4, bottom = top + query.heightCells() * 4;
        for (int cx = query.firstChunkX(); cx <= query.lastChunkX(); cx++) {
            for (int cz = query.firstChunkZ(); cz <= query.lastChunkZ(); cz++) {
                checkCancelled();
                SurfaceChunkSample tile;
                try {
                    tile = MapTileArchive.readSurface(root, cx, cz, query.clientVersion());
                } catch (IOException unreadableTile) {
                    unreadable++;
                    continue;
                }
                if (tile == null) continue;
                loadedTiles++;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int bx = cx * 16 + x, bz = cz * 16 + z, index = x * 16 + z;
                        if (bx < left || bx >= right || bz < top || bz >= bottom) continue;
                        int color = tile.argb().get(index);
                        if (color != 0) surface.add(new MapView.SurfaceCell(bx, bz, tile.heights().get(index), color));
                    }
                }
            }
        }
        List<MapView.Marker> markers = new ArrayList<>();
        try {
            for (MapView.Marker marker : MapTileArchive.readMarkers(root,
                    query.firstChunkX(), query.firstChunkZ(), query.lastChunkX(), query.lastChunkZ())) {
                if (query.contains(Math.floorDiv(marker.x(), 4), Math.floorDiv(marker.z(), 4))) markers.add(marker);
            }
        } catch (IOException unreadableMarkers) {
            unreadable++;
        }
        String message = surface.isEmpty()
                ? loadedTiles == 0
                    ? "当前视窗尚无逐方块地表；手动开启基础或联合取证后，在已加载区域补采。旧群系地图不会自动变细。"
                    : "已读取逐方块地表，但当前视窗没有可见原生地图色；空列保持未知。"
                : "已保存地表 " + surface.size() + " 个实际方块列；原生地图色，未采集区域保持未知。";
        if (unreadable > 0) message = "部分地图索引读取失败（" + unreadable + " 项），对应区域保持未知。" + message;
        return new MapView(query, List.of(), markers, surface, unreadable > 0, message);
    }

    /** 页面关闭后在区块边界尽快终止磁盘查询，不继续读完整视窗。 */
    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("observed map query cancelled");
        }
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static int first(long packed) { return (int) (packed >> 32); }
    private static int second(long packed) { return (int) packed; }

    private record BiomeCandidate(int distance, MapView.Cell cell) {}
}
