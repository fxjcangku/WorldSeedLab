package io.github.fxjcangku.worldseedlab.core.generation;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceKind;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceOrigin;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
import io.github.fxjcangku.worldseedlab.core.probe.ChunkKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 采集质量统计：只统计本次会话已提交写盘队列的完整三维群系区块。
 *
 * <p>32×32 区块的宏区域数量仅是采集足迹，不是覆盖百分比。
 * 高速鞘翅、区块卸载和不同垂直间距会导致未采到的点不可知。
 * 本类不向摘要泄露精确坐标或用户移动轨迹。
 */
public final class SamplingCoverage {
    private final String worldFingerprint;
    private final String dimension;
    private final Set<Long> chunks = new HashSet<>();
    private final Set<Long> macroRegions = new HashSet<>();
    private final Map<Integer, Long> altitudeBands = new HashMap<>();
    private long acceptedQuartSamples;
    private long abandonedUnloadedChunks;
    private long diskBackpressureCount;
    private long abortedDetailChunks;

    public SamplingCoverage(String fingerprint, String dimension) {
        this.worldFingerprint = Objects.requireNonNull(fingerprint);
        this.dimension = Objects.requireNonNull(dimension);
    }

    /**
     * 必须在异步队列接受完整批次后调用；不代表已落盘。
     * 同一区块不同 Y 采样精度可贡献新的样本，但区块覆盖计数不重复增加。
     */
    public void accepted(ChunkKey key, List<EvidenceRecord> records) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("empty coverage evidence");
        }
        for (EvidenceRecord record : records) {
            if (!worldFingerprint.equals(record.worldFingerprint())
                    || !dimension.equals(record.dimension())
                    || record.kind() != EvidenceKind.BIOME_OBSERVATION
                    || record.origin() != EvidenceOrigin.CLIENT_OBSERVATION
                    || (record.x() >> 4) != key.chunkX()
                    || (record.z() >> 4) != key.chunkZ()) {
                throw new IllegalArgumentException("invalid or mixed coverage evidence");
            }
        }
        chunks.add(combine(key.chunkX(), key.chunkZ()));
        macroRegions.add(combine(key.chunkX() >> 5, key.chunkZ() >> 5));
        for (EvidenceRecord record : records) {
            int bandStart = Math.floorDiv(record.y(), 32) * 32;
            altitudeBands.merge(bandStart, 1L, Long::sum);
        }
        acceptedQuartSamples += records.size();
    }

    public void abandonedUnloadedChunk() {
        abandonedUnloadedChunks++;
    }

    public void diskBackpressure() {
        diskBackpressureCount++;
    }

    public void abandonedDetailChunk() {
        abortedDetailChunks++;
    }

    public Snapshot snapshot() {
        return new Snapshot(chunks.size(), macroRegions.size(), acceptedQuartSamples,
                abandonedUnloadedChunks, diskBackpressureCount, abortedDetailChunks,
                Map.copyOf(altitudeBands));
    }

    private static long combine(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /** 只有汇总值与 32 方块高程带计数，不包含坐标/服务器身份信息。 */
    public record Snapshot(
            int uniqueAcceptedChunks,
            int uniqueMacroRegions,
            long acceptedQuartSamples,
            long abandonedUnloadedChunks,
            long diskBackpressureCount,
            long abandonedDetailChunks,
            Map<Integer, Long> altitudeBandSamples
    ) {
        public Snapshot {
            altitudeBandSamples = Map.copyOf(altitudeBandSamples);
        }
    }
}
