package io.github.fxjcangku.worldseedlab.core.generation;

import io.github.fxjcangku.worldseedlab.core.probe.DetailMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 已加载区块的真实方块观测，区别于原始地形噪声。
 *
 * <p>地表列记录高度图指向的顶层方块。地下剖面仅是稀疏柱采样；
 * 含玩家改造、服务端刷方块或资源包伪装的可能性，不可当作未经改变的生成结果。
 */
public record TerrainDetailSample(
        String worldFingerprint,
        String dimension,
        String clientVersion,
        int chunkX, int chunkZ,
        DetailMode mode,
        int verticalStrideBlocks,
        List<SurfaceCell> surface,
        List<VerticalCell> vertical,
        Instant observedAt
) {
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public TerrainDetailSample {
        if (worldFingerprint == null || !HASH.matcher(worldFingerprint).matches()) {
            throw new IllegalArgumentException("invalid world fingerprint");
        }
        requireId(dimension);
        if (clientVersion == null || clientVersion.isBlank() || clientVersion.length() > 32) {
            throw new IllegalArgumentException("invalid client version");
        }
        Objects.requireNonNull(mode);
        if (mode == DetailMode.OFF) {
            throw new IllegalArgumentException("OFF cannot create detail evidence");
        }
        if (verticalStrideBlocks != 0 && (verticalStrideBlocks < 16 || verticalStrideBlocks % 4 != 0)) {
            throw new IllegalArgumentException("invalid vertical stride");
        }
        if (mode == DetailMode.SURFACE && verticalStrideBlocks != 0) {
            throw new IllegalArgumentException("surface-only mode must have zero vertical stride");
        }
        if (mode == DetailMode.STRATA && verticalStrideBlocks < 16) {
            throw new IllegalArgumentException("strata mode requires a measured interval");
        }
        surface = List.copyOf(Objects.requireNonNull(surface));
        vertical = List.copyOf(Objects.requireNonNull(vertical));
        if (surface.size() != 16 || vertical.size() > 256
                || (mode == DetailMode.SURFACE && !vertical.isEmpty())
                || (mode == DetailMode.STRATA && vertical.isEmpty())) {
            throw new IllegalArgumentException("incomplete or excessive terrain detail");
        }
        Objects.requireNonNull(observedAt);
    }

    public TerrainDetailKey key() {
        return new TerrainDetailKey(chunkX, chunkZ, mode);
    }

    public record SurfaceCell(int localX, int localZ, int observedY, String blockId,
                              String blockState, String fluidId, String biomeId) {
        public SurfaceCell {
            if (localX < 0 || localX >= 16 || localZ < 0 || localZ >= 16
                    || localX % 4 != 2 || localZ % 4 != 2) {
                throw new IllegalArgumentException("not a quart-center surface column");
            }
            requireId(blockId);
            requireId(fluidId);
            requireId(biomeId);
            if (blockState == null || blockState.length() > 512) {
                throw new IllegalArgumentException("invalid blockstate diagnostic");
            }
        }
    }

    public record VerticalCell(int localX, int localZ, int observedY,
                               String blockId, String fluidId, String biomeId) {
        public VerticalCell {
            if (localX != 2 && localX != 10) {
                throw new IllegalArgumentException("vertical local x must be 2 or 10");
            }
            if (localZ != 2 && localZ != 10) {
                throw new IllegalArgumentException("vertical local z must be 2 or 10");
            }
            requireId(blockId);
            requireId(fluidId);
            requireId(biomeId);
        }
    }

    private static void requireId(String value) {
        if (value == null || value.length() > 256 || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid registry identifier");
        }
    }
}
