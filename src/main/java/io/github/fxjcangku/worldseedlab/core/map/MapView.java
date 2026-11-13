package io.github.fxjcangku.worldseedlab.core.map;

import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;

/** 可安全跨线程传递给 GUI 的不可变窗口快照。 */
public record MapView(
        MapQuery query,
        List<Cell> cells,
        List<Marker> markers,
        List<SurfaceCell> surfaceCells,
        List<SurfaceRun> surfaceRuns,
        boolean incomplete,
        String message
) {
    public MapView(MapQuery query, List<Cell> cells, List<Marker> markers,
                   boolean incomplete, String message) {
        this(query, cells, markers, List.of(), List.of(), incomplete, message);
    }

    /** 后台查询构造时一次生成同色水平段；渲染帧只读取不可变列表。 */
    public MapView(MapQuery query, List<Cell> cells, List<Marker> markers,
                   List<SurfaceCell> surfaceCells, boolean incomplete, String message) {
        this(query, cells, markers, surfaceCells, horizontalRuns(surfaceCells), incomplete, message);
    }

    public MapView {
        Objects.requireNonNull(query);
        cells = List.copyOf(cells);
        markers = List.copyOf(markers);
        surfaceCells = List.copyOf(surfaceCells);
        surfaceRuns = List.copyOf(surfaceRuns);
        Objects.requireNonNull(message);
    }

    public static MapView empty(MapQuery query, String message) {
        return new MapView(query, List.of(), List.of(), false, message);
    }

    /** quartX/Z 为整数网格位置，observedY 是实际观测 Y，不是预测高度。 */
    public record Cell(int quartX, int quartZ, int observedY,
                       String biomeId, int surfaceY) {
    }

    /** 手动建筑标记只是玩家观察坐标，并非真正的结构生成起点。 */
    public record Marker(int x, int y, int z, String structureId) {
    }

    /** 每个元素严格对应一个实际方块列，不把 quart 群系格放大充当地表细节。 */
    public record SurfaceCell(int blockX, int blockZ, int observedY, int argb) { }

    /** 仅合并同一行、严格连续且同色的实际列；长度中绝不包含未知位置。 */
    public record SurfaceRun(int blockX, int blockZ, int length, int argb) { }

    private static List<SurfaceRun> horizontalRuns(List<SurfaceCell> cells) {
        if (cells.isEmpty()) return List.of();
        var sorted = cells.stream().sorted(Comparator.comparingInt(SurfaceCell::blockZ)
                .thenComparingInt(SurfaceCell::blockX)).toList();
        List<SurfaceRun> runs = new ArrayList<>();
        int x = 0, z = 0, length = 0, color = 0;
        for (SurfaceCell cell : sorted) {
            if (length > 0 && cell.blockZ() == z && cell.blockX() == x + length && cell.argb() == color) {
                length++;
            } else {
                if (length > 0) runs.add(new SurfaceRun(x, z, length, color));
                x = cell.blockX();
                z = cell.blockZ();
                length = 1;
                color = cell.argb();
            }
        }
        runs.add(new SurfaceRun(x, z, length, color));
        return List.copyOf(runs);
    }
}
