package io.github.fxjcangku.worldseedlab.core.generation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 精细取证会话统计。只统计接受写入队列的记录，非生成算法的概率输出。
 * 快照不含服务器地址、区块坐标、玩家轨迹。
 */
public final class RichObservationStats {
    private final String fingerprint;
    private final String dimension;
    private long observedChunks;
    private long fluidColumns;
    private long undergroundAirObservations;
    private final Map<String, Long> surfaceBlocks = new HashMap<>();
    private final Map<String, Long> surfaceBiomes = new HashMap<>();
    private final Map<String, Long> deepBlocks = new HashMap<>();

    public RichObservationStats(String fingerprint, String dimension) {
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.dimension = Objects.requireNonNull(dimension);
    }

    public void observe(RichChunkSample sample) {
        if (!fingerprint.equals(sample.worldFingerprint()) ||
                !dimension.equals(sample.dimension())) {
            throw new IllegalArgumentException("rich evidence crossed the current world");
        }
        observedChunks++;
        fluidColumns += sample.fluidColumns();
        sample.surfaceBlockIds().forEach(id -> surfaceBlocks.merge(id, 1L, Long::sum));
        sample.surfaceBiomeIds().forEach(id -> surfaceBiomes.merge(id, 1L, Long::sum));
        for (RichChunkSample.DeepLayer layer : sample.deepLayers()) {
            deepBlocks.merge(layer.blockId(), 1L, Long::sum);
            if (layer.blockId().equals("minecraft:air") ||
                    layer.blockId().equals("minecraft:cave_air")) {
                undergroundAirObservations++;
            }
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(observedChunks, fluidColumns, undergroundAirObservations,
                Map.copyOf(surfaceBlocks), Map.copyOf(surfaceBiomes), Map.copyOf(deepBlocks));
    }

    public record Snapshot(
            long observedChunks, long fluidColumns, long undergroundAirObservations,
            Map<String, Long> surfaceBlocks,
            Map<String, Long> surfaceBiomes,
            Map<String, Long> deepBlocks
    ) {
    }
}
