package io.github.fxjcangku.worldseedlab.core.generation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 精细观测：客户端已加载区块的多高度图、地表方块/群系/流体与稀疏地下岩层。
 *
 * <p>只能说明采样时看到的状态；玩家建筑、插件改图、区块迁移可能污染结果。
 * 不记录方块实体、NBT、告示牌内容、箱子内容，也不声称取得服务器生成器参数。
 * 16 个列样本的顺序为 localX=2,6,10,14 外层、localZ=2,6,10,14 内层。
 */
public record RichChunkSample(
        String worldFingerprint, String dimension, String clientVersion,
        int chunkX, int chunkZ, int minBuildY, int maxBuildYExclusive,
        List<Integer> surfaceY,
        List<Integer> oceanFloorY,
        List<Integer> motionBlockingY,
        List<String> surfaceBlockIds,
        List<String> oceanFloorBlockIds,
        List<String> surfaceBiomeIds,
        List<String> surfaceFluidIds,
        List<DeepLayer> deepLayers,
        Instant observedAt
) {
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public RichChunkSample {
        if (worldFingerprint == null || !HASH.matcher(worldFingerprint).matches()) {
            throw new IllegalArgumentException("invalid world fingerprint");
        }
        validateId(dimension);
        if (clientVersion == null || clientVersion.isBlank() || clientVersion.length() > 32) {
            throw new IllegalArgumentException("invalid client version");
        }
        if (maxBuildYExclusive <= minBuildY || maxBuildYExclusive - minBuildY > 4096) {
            throw new IllegalArgumentException("invalid dimension vertical bounds");
        }
        Objects.requireNonNull(observedAt, "observedAt");

        surfaceY = List.copyOf(surfaceY);
        oceanFloorY = List.copyOf(oceanFloorY);
        motionBlockingY = List.copyOf(motionBlockingY);
        surfaceBlockIds = List.copyOf(surfaceBlockIds);
        oceanFloorBlockIds = List.copyOf(oceanFloorBlockIds);
        surfaceBiomeIds = List.copyOf(surfaceBiomeIds);
        surfaceFluidIds = List.copyOf(surfaceFluidIds);
        deepLayers = List.copyOf(deepLayers);

        for (List<Integer> heights : List.of(surfaceY, oceanFloorY, motionBlockingY)) {
            if (heights.size() != 16) {
                throw new IllegalArgumentException("each quart column requires 16 heights");
            }
            for (int height : heights) {
                if (height < minBuildY - 1 || height > maxBuildYExclusive + 1) {
                    throw new IllegalArgumentException("heightmap sample is out of bounds");
                }
            }
        }
        for (List<String> ids : List.of(surfaceBlockIds, oceanFloorBlockIds,
                surfaceBiomeIds, surfaceFluidIds)) {
            if (ids.size() != 16) {
                throw new IllegalArgumentException("each quart column requires 16 registry IDs");
            }
            ids.forEach(RichChunkSample::validateId);
        }
        if (deepLayers.size() != 16) {
            throw new IllegalArgumentException("requires 4 positions x 4 depths");
        }
        for (DeepLayer sample : deepLayers) {
            if (sample.y() < minBuildY || sample.y() >= maxBuildYExclusive) {
                throw new IllegalArgumentException("deep layer Y is outside the dimension");
            }
        }
    }

    private static void validateId(String id) {
        if (id == null || id.length() > 256 || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid namespaced registry ID");
        }
    }

    /** 从已经传到客户端的当前方块状态中得到的岩层观察，非服务器原始生成资料。 */
    public record DeepLayer(int localX, int localZ, int y, String blockId) {
        public DeepLayer {
            if (localX != 2 && localX != 10 || localZ != 2 && localZ != 10) {
                throw new IllegalArgumentException("deep sampling uses four fixed quart positions");
            }
            validateId(blockId);
        }
    }

    public int highestSurface() {
        return surfaceY.stream().mapToInt(Integer::intValue).max().orElseThrow();
    }

    public int deepestOceanFloor() {
        return oceanFloorY.stream().mapToInt(Integer::intValue).min().orElseThrow();
    }

    public long fluidColumns() {
        return surfaceFluidIds.stream().filter(id -> !id.equals("minecraft:empty")).count();
    }
}
