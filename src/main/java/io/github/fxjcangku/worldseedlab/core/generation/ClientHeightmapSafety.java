package io.github.fxjcangku.worldseedlab.core.generation;

import java.util.List;

/**
 * 客户端高度图可用性守卫。FULL区块不保证保留所有服务端高度图。
 *
 * <p>缺失OCEAN_FLOOR时，客户端可能返回minBuildY-1哨兵值。
 * 它不能被解释成海床高度，更不能通过坐标裁剪伪造成基岩海床。
 * 水下材料扫描改为实际水面方块确认和有界深度带，不推断天然来源。
 */
public final class ClientHeightmapSafety {
    public static final String UNAVAILABLE_OCEAN_FLOOR_ID = "worldseedlab:heightmap_unavailable";

    private static final List<Integer> FALLBACK_WATER_DEPTHS =
            List.of(-64, -56, -48, -40, -32, -28, -24, -20);

    private ClientHeightmapSafety() {}

    public static boolean usableOceanFloor(
            int minBuildY, int worldSurfaceHeight, int oceanFloorHeight
    ) {
        return worldSurfaceHeight > minBuildY
                && oceanFloorHeight > minBuildY
                && oceanFloorHeight <= worldSurfaceHeight;
    }

    /**
     * 缺失真实海床高度时，相对已观测水面的额外有限深度。
     * 仅用于有界材料探测；这些位置不是海床真值或种子约束。
     */
    public static List<Integer> fallbackWaterDepths() {
        return FALLBACK_WATER_DEPTHS;
    }
}
