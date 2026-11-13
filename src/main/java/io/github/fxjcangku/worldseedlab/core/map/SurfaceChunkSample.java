package io.github.fxjcangku.worldseedlab.core.map;

import io.github.fxjcangku.worldseedlab.core.generation.DimensionProfile;
import java.time.Instant;
import java.util.List;

/** 一个已加载完整区块的逐方块原生地图色；不包含预测值或群系插值。 */
public record SurfaceChunkSample(String worldFingerprint, String dimension, String clientVersion,
                                 int chunkX, int chunkZ, int minY, int maxYExclusive,
                                 List<Integer> heights, List<Integer> argb, Instant observedAt) {
    public SurfaceChunkSample {
        new DimensionProfile(worldFingerprint, dimension, clientVersion, minY, maxYExclusive,
                minY, false, false, null, observedAt);
        if (!clientVersion.equals("26.1.2") && !clientVersion.equals("26.2")) {
            throw new IllegalArgumentException("unsupported surface map game version");
        }
        heights = List.copyOf(heights);
        argb = List.copyOf(argb);
        if (heights.size() != 256 || argb.size() != 256) {
            throw new IllegalArgumentException("surface map requires all 256 block columns");
        }
        for (int i = 0; i < 256; i++) {
            int y = heights.get(i), color = argb.get(i);
            if (y < minY - 1 || y >= maxYExclusive
                    || (color != 0 && (color >>> 24) != 255)
                    || (y < minY && color != 0)) {
                throw new IllegalArgumentException("invalid observed surface map column");
            }
        }
    }
}
