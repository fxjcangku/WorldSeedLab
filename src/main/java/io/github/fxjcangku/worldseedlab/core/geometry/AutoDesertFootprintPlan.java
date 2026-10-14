package io.github.fxjcangku.worldseedlab.core.geometry;

import java.util.Objects;

/**
 * P3.6 自动沙漠神殿证据定位：只把实际客户端观察到的中央蓝色陶瓦作为空间锚点。
 * 该锚点不等于 StructureStart，不能证明天然生成或后台 Seed。
 *
 * <p>玩家无需选择两个角。观察到蓝色陶瓦后仅建议一个29×29粗ROI，
 * 精确21×21完整外轮廓仍交给 P3.5 的真实方块分类器逐列复核。
 * 自定义结构、人工蓝陶瓦、遮挡区域一律允许失败，绝不补测未知区块。
 */
public final class AutoDesertFootprintPlan {
    private static final int ROI_HALF_WIDTH = 14;
    private static final int HEIGHT = 32;
    public static final int BLUE_SEARCH_HEIGHT = HEIGHT;
    private static final int BLUE_SCAN_BELOW_SURFACE = 24;

    private AutoDesertFootprintPlan() {}

    public record BlueTerracottaAnchor(int x, int y, int z) {}

    public record Survey(StructureFootprintInverter.BlockBounds roughBounds,
                         int minY, int maxY) {
        public Survey {
            Objects.requireNonNull(roughBounds);
            if (roughBounds.maxX() - roughBounds.minX() + 1 != 29
                    || roughBounds.maxZ() - roughBounds.minZ() + 1 != 29
                    || maxY - minY + 1 != HEIGHT) {
                throw new IllegalArgumentException("P3.6 自动候选取证范围不是受控29×29×32");
            }
        }
    }

    /**
     * 依据已接收 FULL 区块的 WORLD_SURFACE 高度图选取每列32格搜索带，
     * 不再使用玩家鞘翅高度。仅属于地表相邻材料检查，不能代替地下普查。
     *
     * @param firstFreeY WORLD_SURFACE 返回的第一格空气高度
     * @param buildMaxYExclusive 建造高度不含上界
     */
    public record ScanBand(int minY, int maxY) {
        public ScanBand {
            if ((long) maxY - minY + 1 != BLUE_SEARCH_HEIGHT) {
                throw new IllegalArgumentException("P3.6 地表材料扫描必须恰好32格高");
            }
        }

        public boolean includes(int y) { return minY <= y && y <= maxY; }
    }

    public static ScanBand aroundObservedSurface(int firstFreeY,
                                                  int buildMinY, int buildMaxYExclusive) {
        long totalHeight = (long) buildMaxYExclusive - buildMinY;
        if (totalHeight < BLUE_SEARCH_HEIGHT) {
            throw new IllegalArgumentException("P3.6 维度高度不足32格");
        }
        long proposedLow = (long) firstFreeY - BLUE_SCAN_BELOW_SURFACE;
        long low = Math.max((long) buildMinY,
                Math.min(proposedLow, (long) buildMaxYExclusive - BLUE_SEARCH_HEIGHT));
        return new ScanBand((int) low, (int) (low + BLUE_SEARCH_HEIGHT - 1));
    }

    public static Survey fromObservedBlue(BlueTerracottaAnchor anchor,
                                           int buildMinY, int buildMaxYExclusive) {
        Objects.requireNonNull(anchor);
        try {
            int lowX = Math.subtractExact(anchor.x(), ROI_HALF_WIDTH);
            int highX = Math.addExact(anchor.x(), ROI_HALF_WIDTH);
            int lowZ = Math.subtractExact(anchor.z(), ROI_HALF_WIDTH);
            int highZ = Math.addExact(anchor.z(), ROI_HALF_WIDTH);
            int highY = Math.addExact(anchor.y(), HEIGHT - 1);
            if (anchor.y() < buildMinY || highY >= buildMaxYExclusive
                    || buildMinY >= buildMaxYExclusive) {
                throw new IllegalArgumentException("观察到的蓝陶瓦无法覆盖完整32格高度");
            }
            return new Survey(
                    new StructureFootprintInverter.BlockBounds(
                            lowX, lowZ, highX, highZ), anchor.y(), highY);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("蓝陶瓦接近世界坐标极限，拒绝自动框选", overflow);
        }
    }
}
