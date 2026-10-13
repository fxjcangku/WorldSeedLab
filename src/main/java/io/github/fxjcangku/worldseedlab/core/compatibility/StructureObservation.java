package io.github.fxjcangku.worldseedlab.core.compatibility;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 建筑取证模型：玩家观察的方块坐标与生成起始区块严格分离。
 *
 * <p>仅看见一个建筑，不能知道其服务器自然生成来源；玩家观察点
 * 无权提供结构起点。外形测量得出的候选区域也只能作为约束范围。
 */
public record StructureObservation(
        String worldFingerprint,
        String dimension,
        String clientVersion,
        String structureId,
        int observedBlockX,
        int observedBlockY,
        int observedBlockZ,
        Origin origin,
        ChunkBounds possibleStartChunks,
        Instant observedAt
) {
    private static final Pattern FINGERPRINT = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public StructureObservation {
        if (worldFingerprint == null || !FINGERPRINT.matcher(worldFingerprint).matches()) {
            throw new IllegalArgumentException("invalid local world fingerprint");
        }
        for (String id : new String[] {dimension, structureId}) {
            if (id == null || !ID.matcher(id).matches() || id.length() > 256) {
                throw new IllegalArgumentException("invalid namespaced identifier");
            }
        }
        if (!"26.1.2".equals(clientVersion) && !"26.2".equals(clientVersion)) {
            throw new IllegalArgumentException("unsupported evidence version");
        }
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(observedAt, "observedAt");
        if (origin == Origin.MANUAL_SIGHTING && possibleStartChunks != null) {
            throw new IllegalArgumentException("manual sighting cannot claim start chunks");
        }
        if (origin == Origin.GEOMETRY_ESTIMATE && possibleStartChunks == null) {
            throw new IllegalArgumentException("geometry estimate requires a bounded region");
        }
    }

    public enum Origin {
        MANUAL_SIGHTING,
        GEOMETRY_ESTIMATE
    }

    /** 块坐标需先经建筑外形分析转换到候选区块范围，不得直接除以 16 伪造起点。 */
    public record ChunkBounds(int minX, int minZ, int maxX, int maxZ) {
        public ChunkBounds {
            if (minX > maxX || minZ > maxZ
                    || (long) maxX - minX > 64 || (long) maxZ - minZ > 64) {
                throw new IllegalArgumentException("candidate region is reversed or unbounded");
            }
        }

        public boolean contains(int chunkX, int chunkZ) {
            return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
        }

        public boolean isExact() {
            return minX == maxX && minZ == maxZ;
        }
    }
}
