package io.github.fxjcangku.worldseedlab.core.map;

/**
 * 颜色只为区分观察到的群系 ID。自定义群系用固定哈希色，
 * 并不声称该注册键代表的真实贴图、植被色或服务器生成器类型。
 */
public final class MapPalette {
    private MapPalette() {}

    public static int biome(String id) {
        if (id == null) return 0xFF263342;
        if (id.startsWith("minecraft:")) {
            if (id.contains("ocean") || id.contains("river")) return 0xFF247AB6;
            if (id.contains("desert") || id.contains("badlands")) return 0xFFD3A56A;
            if (id.contains("snow") || id.contains("ice") || id.contains("frozen"))
                return 0xFFC8E5EF;
            if (id.contains("swamp") || id.contains("mangrove")) return 0xFF527961;
            if (id.contains("forest") || id.contains("jungle") || id.contains("taiga"))
                return 0xFF38765A;
            if (id.contains("mountain") || id.contains("peaks") || id.contains("stony"))
                return 0xFF8C9BA5;
            if (id.contains("cave") || id.contains("dark")) return 0xFF61748B;
            if (id.contains("nether") || id.contains("crimson")) return 0xFFAD5961;
            if (id.contains("end")) return 0xFFC7D0A3;
            return 0xFF77AD70;
        }
        int hash = id.hashCode();
        int r = 70 + (hash & 0x7f);
        int g = 76 + ((hash >>> 8) & 0x7f);
        int b = 83 + ((hash >>> 16) & 0x7f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 这里只可视化实际存档中的高度图数值，不是未探索地形预测。 */
    public static int elevation(int y) {
        if (y < 48) return 0xFF215E8A;
        if (y < 66) return 0xFF478F8D;
        if (y < 100) return 0xFF5E9769;
        if (y < 155) return 0xFF9E9868;
        if (y < 225) return 0xFF9A9FAB;
        return 0xFFD5E5EE;
    }
}
