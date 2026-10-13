package io.github.fxjcangku.worldseedlab.core.generation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 每区块的 WORLD_SURFACE 高度图稀疏样本（16 列，按 quart 中心）。
 *
 * <p>高度图的顶部方块不代表全部地形形状，尤其无法证明空洞、地下群系、
 * 浮空岛下层和服务器自定义噪声的生成过程。此记录不得直接进入 Seed 求解器。
 */
public record TerrainChunkSample(
        String worldFingerprint,
        String dimension,
        String clientVersion,
        int chunkX,
        int chunkZ,
        int minBuildY,
        int maxBuildYExclusive,
        int seaLevel,
        List<Integer> surfaceHeights,
        Instant observedAt
) {
    public TerrainChunkSample {
        new DimensionProfile(worldFingerprint, dimension, clientVersion,
                minBuildY, maxBuildYExclusive, seaLevel,
                false, false, false, Objects.requireNonNull(observedAt));
        surfaceHeights = List.copyOf(Objects.requireNonNull(surfaceHeights, "surfaceHeights"));
        if (surfaceHeights.size() != 16) {
            throw new IllegalArgumentException("requires 16 terrain quart-grid height samples");
        }
        for (Integer height : surfaceHeights) {
            if (height == null || height < minBuildY - 1 || height > maxBuildYExclusive + 1) {
                throw new IllegalArgumentException("heightmap sample is outside build bounds");
            }
        }
    }

    public int minSurfaceY() {
        return surfaceHeights.stream().mapToInt(Integer::intValue).min().orElseThrow();
    }

    public int maxSurfaceY() {
        return surfaceHeights.stream().mapToInt(Integer::intValue).max().orElseThrow();
    }

    public double meanSurfaceY() {
        return surfaceHeights.stream().mapToInt(Integer::intValue).average().orElseThrow();
    }

    public int relief() {
        return maxSurfaceY() - minSurfaceY();
    }

    public String chunkLabel() {
        return chunkX + "," + chunkZ;
    }
}
