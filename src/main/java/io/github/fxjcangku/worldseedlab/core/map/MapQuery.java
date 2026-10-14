package io.github.fxjcangku.worldseedlab.core.map;

import java.util.Objects;

/**
 * 指定窗口大小、世界中心和层类型。横向每个地图格点是实际 4×4 方块群系采样。
 * 不把视野以外或从未采样到的区域当作已知。
 */
public record MapQuery(int centerX, int centerZ, int widthCells, int heightCells,
                       MapLayer layer, int targetY) {
    public MapQuery {
        Objects.requireNonNull(layer);
        if (widthCells < 1 || widthCells > 128
                || heightCells < 1 || heightCells > 96) {
            throw new IllegalArgumentException("map viewport out of safe bounds");
        }
        if (targetY < -4096 || targetY > 8192) {
            throw new IllegalArgumentException("map altitude out of range");
        }
    }

    public int leftQuart() {
        return Math.floorDiv(centerX, 4) - widthCells / 2;
    }

    public int topQuart() {
        return Math.floorDiv(centerZ, 4) - heightCells / 2;
    }

    public boolean contains(int quartX, int quartZ) {
        return quartX >= leftQuart() && quartX < leftQuart() + widthCells
                && quartZ >= topQuart() && quartZ < topQuart() + heightCells;
    }

    public int firstChunkX() { return Math.floorDiv(leftQuart(), 4); }
    public int lastChunkX() { return Math.floorDiv(leftQuart() + widthCells - 1, 4); }
    public int firstChunkZ() { return Math.floorDiv(topQuart(), 4); }
    public int lastChunkZ() { return Math.floorDiv(topQuart() + heightCells - 1, 4); }

    public MapQuery moved(int dxBlocks, int dzBlocks) {
        return new MapQuery(centerX + dxBlocks, centerZ + dzBlocks,
                widthCells, heightCells, layer, targetY);
    }

    public MapQuery withLayer(MapLayer next) {
        return new MapQuery(centerX, centerZ, widthCells, heightCells, next, targetY);
    }

    public MapQuery withY(int nextY) {
        return new MapQuery(centerX, centerZ, widthCells, heightCells,
                layer, Math.max(-4096, Math.min(8192, nextY)));
    }
}
