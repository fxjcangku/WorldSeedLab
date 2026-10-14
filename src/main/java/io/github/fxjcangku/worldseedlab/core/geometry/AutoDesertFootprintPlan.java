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
