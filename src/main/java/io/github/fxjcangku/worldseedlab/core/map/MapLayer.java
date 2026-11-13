package io.github.fxjcangku.worldseedlab.core.map;

/** 地图使用真实观察证据；未知区域绝不以种子猜测结果填充。 */
public enum MapLayer {
    SURFACE_COLOR("地表地图"),
    SURFACE_BIOME("地表群系"),
    ALTITUDE_BIOME("指定高度群系"),
    TERRAIN_HEIGHT("地形高度");

    private final String label;

    MapLayer(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public MapLayer next() {
        return switch (this) {
            case SURFACE_COLOR -> SURFACE_BIOME;
            case SURFACE_BIOME -> ALTITUDE_BIOME;
            case ALTITUDE_BIOME -> TERRAIN_HEIGHT;
            case TERRAIN_HEIGHT -> SURFACE_COLOR;
        };
    }
}
