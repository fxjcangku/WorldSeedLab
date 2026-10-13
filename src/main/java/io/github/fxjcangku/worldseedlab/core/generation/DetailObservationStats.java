package io.github.fxjcangku.worldseedlab.core.generation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 扩展观测的安全统计摘要。只处理主线程已接受写入队列的真实观测结果。
 * 不保存玩家行动轨迹、服务器原始地址、物品内容或任何 Seed 猜测。
 */
public final class DetailObservationStats {
    private final String worldFingerprint;
    private final String dimension;
    private long acceptedChunks;
    private long surfaceFluidColumns;
    private long verticalCells;
    private final Map<String, Long> surfaceBlocks = new HashMap<>();
    private final Map<String, Long> oceanFloorBlocks = new HashMap<>();
    private final Map<String, Long> surfaceBiomes = new HashMap<>();
    private final Map<String, Long> verticalBlocks = new HashMap<>();

    public DetailObservationStats(String worldFingerprint, String dimension) {
        this.worldFingerprint = Objects.requireNonNull(worldFingerprint);
        this.dimension = Objects.requireNonNull(dimension);
    }

    public void observe(TerrainDetailSample sample) {
        if (!worldFingerprint.equals(sample.worldFingerprint()) ||
                !dimension.equals(sample.dimension())) {
            throw new IllegalArgumentException("detail evidence crossed world or dimension scope");
        }
        acceptedChunks++;
        for (TerrainDetailSample.SurfaceCell cell : sample.surface()) {
            surfaceBlocks.merge(cell.blockId(), 1L, Long::sum);
            oceanFloorBlocks.merge(cell.oceanFloorBlockId(), 1L, Long::sum);
            surfaceBiomes.merge(cell.biomeId(), 1L, Long::sum);
            if (!cell.fluidId().equals("minecraft:empty")) surfaceFluidColumns++;
        }
        for (TerrainDetailSample.VerticalCell cell : sample.vertical()) {
            verticalBlocks.merge(cell.blockId(), 1L, Long::sum);
            verticalCells++;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(acceptedChunks, surfaceFluidColumns, verticalCells,
                Map.copyOf(surfaceBlocks), Map.copyOf(oceanFloorBlocks),
                Map.copyOf(surfaceBiomes), Map.copyOf(verticalBlocks));
    }

    /** 不含区块坐标的独立不可变快照。 */
    public record Snapshot(long acceptedChunks, long surfaceFluidColumns, long verticalCells,
                           Map<String, Long> surfaceBlocks,
                           Map<String, Long> oceanFloorBlocks,
                           Map<String, Long> surfaceBiomes,
                           Map<String, Long> verticalBlocks) {
    }
}
