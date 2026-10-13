package io.github.fxjcangku.worldseedlab.core.generation;

import java.util.Objects;

/**
 * 只聚合已观测到的地形高度样本，不把统计相关性当成种子推导证据。
 *
 * <p>本类由客户端主线程单独写入；GUI 可读取只读快照。
 */
public final class GenerationDiagnostics {
    private final DimensionProfile scope;
    private long terrainChunks;
    private int lowestSurface = Integer.MAX_VALUE;
    private int highestSurface = Integer.MIN_VALUE;
    private int largestWithinChunkRelief;
    private long sumOfWithinChunkRelief;
    private long highReliefChunks;

    public GenerationDiagnostics(DimensionProfile scope) {
        this.scope = Objects.requireNonNull(scope);
    }

    public void observe(TerrainChunkSample sample) {
        if (!scope.worldFingerprint().equals(sample.worldFingerprint())
                || !scope.dimension().equals(sample.dimension())
                || !scope.clientVersion().equals(sample.clientVersion())) {
            throw new IllegalArgumentException("terrain observation crossed a dimension scope");
        }
        terrainChunks++;
        lowestSurface = Math.min(lowestSurface, sample.minSurfaceY());
        highestSurface = Math.max(highestSurface, sample.maxSurfaceY());
        largestWithinChunkRelief = Math.max(largestWithinChunkRelief, sample.relief());
        sumOfWithinChunkRelief += sample.relief();
        if (sample.relief() >= 32) {
            highReliefChunks++;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                terrainChunks,
                terrainChunks == 0 ? 0 : lowestSurface,
                terrainChunks == 0 ? 0 : highestSurface,
                largestWithinChunkRelief,
                terrainChunks == 0 ? 0 : (double) sumOfWithinChunkRelief / terrainChunks,
                highReliefChunks
        );
    }

    /** 注意：统计结果不会自动指向已知原版生成器。 */
    public record Snapshot(
            long terrainChunks,
            int lowestSurface,
            int highestSurface,
            int maximumChunkRelief,
            double averageChunkRelief,
            long highReliefChunks
    ) {
    }
}
