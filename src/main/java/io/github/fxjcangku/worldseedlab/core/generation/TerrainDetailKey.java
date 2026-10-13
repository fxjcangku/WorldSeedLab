package io.github.fxjcangku.worldseedlab.core.generation;

import io.github.fxjcangku.worldseedlab.core.probe.DetailMode;

/** 地表详情与地下剖面使用独立进度键，升级档位不会丢失旧证据。 */
public record TerrainDetailKey(int chunkX, int chunkZ, DetailMode mode) {
    public TerrainDetailKey {
        if (mode == null || mode == DetailMode.OFF) {
            throw new IllegalArgumentException("detail index requires an enabled mode");
        }
    }

    public String asLine() {
        return chunkX + "," + chunkZ + "," + mode.name();
    }

    public static TerrainDetailKey parse(String line) {
        String[] parts = line.split(",", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid detail journal");
        }
        return new TerrainDetailKey(Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]), DetailMode.valueOf(parts[2]));
    }
}
