package io.github.fxjcangku.worldseedlab.client.probe;

import io.github.fxjcangku.worldseedlab.core.generation.DimensionProfile;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkKey;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkSample;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * P2 地形 / 维度观测器。
 *
 * <p>只读取 ClientLevel 和实际已加载区块，绝不生成或请求新的区块。
 * HEIGHTMAP(WORLD_SURFACE) 只表示该列的最高表面特征，
 * 不等于服务器 NoiseSettings / 自定义生成器参数 / 世界 Seed。
 */
public final class TerrainObserver {
    private TerrainObserver() {
    }

    /** 从真实的客户端维度属性生成只读档案，不读取后台世界配置。 */
    public static DimensionProfile dimensionProfile(
            ClientLevel level, String fingerprint, String gameVersion
    ) {
        var attributes = level.dimensionType();
        return new DimensionProfile(
                fingerprint,
                level.dimension().identifier().toString(),
                gameVersion,
                level.getMinY(),
                level.getMaxY(),
                level.getSeaLevel(),
                attributes.hasSkyLight(),
                attributes.hasCeiling(),
                null, // 26.1.2/26.2 未验证此 API，明确记录未知而非错误填假。
                Instant.now()
        );
    }

    /**
     * 每区块仅采 16 个 WORLD_SURFACE 列，不读取玩家移动轨迹。
     * 样本坐标严格落在 4×4 方块水平网格的中心点。
     */
    public static TerrainChunkSample observe(
            ClientLevel level, DimensionProfile profile, TerrainChunkKey key
    ) {
        var chunk = level.getChunkSource().getChunk(
                key.x(), key.z(), ChunkStatus.FULL, false);
        if (chunk == null) return null;

        List<Integer> surface = new ArrayList<>(16);
        for (int localX = 2; localX < 16; localX += 4) {
            for (int localZ = 2; localZ < 16; localZ += 4) {
                surface.add(chunk.getHeight(
                        Heightmap.Types.WORLD_SURFACE, localX, localZ));
            }
        }
        return new TerrainChunkSample(
                profile.worldFingerprint(),
                profile.dimension(),
                profile.clientVersion(),
                key.x(), key.z(),
                profile.minBuildY(),
                profile.maxBuildYExclusive(),
                profile.seaLevel(),
                surface,
                Instant.now()
        );
    }
}
