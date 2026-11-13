package io.github.fxjcangku.worldseedlab.client.probe;

import io.github.fxjcangku.worldseedlab.core.generation.DimensionProfile;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkKey;
import io.github.fxjcangku.worldseedlab.core.map.SurfaceSamplingCursor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/** 两版 Mojmap 真实地图色适配；只读已加载 FULL，绝不访问生成器或请求新区块。 */
public final class LoadedSurfaceMapCursor {
    private LoadedSurfaceMapCursor() { }

    public static SurfaceSamplingCursor create(ClientLevel level, DimensionProfile profile,
                                                TerrainChunkKey key) {
        LevelChunk chunk = level.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false);
        // getHeight 在高度图缺失时会隐式 prime 并扫描整块；新图层只接受客户端已具备的高度图。
        if (chunk == null || !chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)) return null;
        return new SurfaceSamplingCursor(profile, key, new SurfaceSamplingCursor.Reader() {
            @Override public boolean stillLoaded() {
                return level.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false) == chunk
                        && chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE);
            }

            @Override public int surfaceY(int x, int z) {
                // 两版真实 ChunkAccess.getHeight 已经是顶部方块 Y，不额外减一。
                return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            }

            @Override public int argb(int x, int y, int z) {
                BlockPos pos = new BlockPos((key.x() << 4) + x, y, (key.z() << 4) + z);
                var state = chunk.getBlockState(pos);
                // 两版 getMapColor 都读取 BlockState 自身缓存颜色，不扫描邻块。
                // HIGH 使用原生完整亮度；颜色为原版地图材质色，不声称是纹理卫星图。
                return state.getMapColor(chunk, pos).calculateARGBColor(MapColor.Brightness.HIGH);
            }
        });
    }
}
