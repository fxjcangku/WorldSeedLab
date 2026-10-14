package io.github.fxjcangku.worldseedlab.core.map;

import java.util.List;
import java.util.Objects;

/** 可安全跨线程传递给 GUI 的不可变窗口快照。 */
public record MapView(
        MapQuery query,
        List<Cell> cells,
        List<Marker> markers,
        boolean incomplete,
        String message
) {
    public MapView {
        Objects.requireNonNull(query);
        cells = List.copyOf(cells);
        markers = List.copyOf(markers);
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
}
