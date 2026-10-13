package io.github.fxjcangku.worldseedlab.client.probe;

import io.github.fxjcangku.worldseedlab.core.generation.DimensionProfile;
import io.github.fxjcangku.worldseedlab.core.generation.RichChunkSample;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 加强取证：只从客户端已加载的区块提取空间格点，不会强制生成或向服务器请求区块。
 *
 * <p>三种高度图各取 16 点；每列采集地表/海床方块、地表群系与流体注册 ID。
 * 在四个固定水平位置，各读取地表以下 8/24/48/96 格，记录真实方块 ID。
 * 无箱子 NBT、告示牌文字或玩家资料；所有读操作在主游戏线程完成。
 */
public final class RichTerrainObserver {
    private static final int[] COLUMNS = {2, 6, 10, 14};
    private static final int[] DEEP_CORNERS = {2, 10};
    private static final int[] DEPTHS = {8, 24, 48, 96};

    private RichTerrainObserver() {
    }

    public static RichChunkSample observe(ClientLevel level, DimensionProfile profile,
                                          TerrainChunkKey target) {
        var chunk = level.getChunkSource().getChunk(
                target.x(), target.z(), ChunkStatus.FULL, false);
        if (chunk == null) return null;

        List<Integer> surface = new ArrayList<>(16);
        List<Integer> floor = new ArrayList<>(16);
        List<Integer> motion = new ArrayList<>(16);
        List<String> topBlocks = new ArrayList<>(16);
        List<String> seabedBlocks = new ArrayList<>(16);
        List<String> topBiomes = new ArrayList<>(16);
        List<String> topFluids = new ArrayList<>(16);

        int minY = profile.minBuildY();
        int maxY = profile.maxBuildYExclusive() - 1;

        for (int localX : COLUMNS) {
            for (int localZ : COLUMNS) {
                int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
                int floorY = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, localX, localZ);
                int motionY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, localX, localZ);
                surface.add(surfaceY);
                floor.add(floorY);
                motion.add(motionY);

                int x = (target.x() << 4) + localX;
                int z = (target.z() << 4) + localZ;
                BlockPos top = new BlockPos(x, clamp(surfaceY - 1, minY, maxY), z);
                BlockPos bottom = new BlockPos(x, clamp(floorY - 1, minY, maxY), z);
                var block = chunk.getBlockState(top);
                var bed = chunk.getBlockState(bottom);

                topBlocks.add(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());
                seabedBlocks.add(BuiltInRegistries.BLOCK.getKey(bed.getBlock()).toString());
                topFluids.add(block.getFluidState().isEmpty()
                        ? "minecraft:empty"
                        : BuiltInRegistries.FLUID.getKey(block.getFluidState().getType()).toString());
                topBiomes.add(level.getBiome(top).unwrapKey()
                        .map(key -> key.identifier().toString())
                        .orElseThrow(() -> new IllegalStateException("surface biome has no registry ID")));
            }
        }

        List<RichChunkSample.DeepLayer> layers = new ArrayList<>(16);
        for (int localX : DEEP_CORNERS) {
            for (int localZ : DEEP_CORNERS) {
                int topY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) - 1;
                for (int depth : DEPTHS) {
                    int y = clamp(topY - depth, minY, maxY);
                    BlockPos pos = new BlockPos((target.x() << 4) + localX,
                            y, (target.z() << 4) + localZ);
                    String id = BuiltInRegistries.BLOCK.getKey(
                            chunk.getBlockState(pos).getBlock()).toString();
                    layers.add(new RichChunkSample.DeepLayer(localX, localZ, y, id));
                }
            }
        }

        return new RichChunkSample(
                profile.worldFingerprint(), profile.dimension(), profile.clientVersion(),
                target.x(), target.z(), profile.minBuildY(), profile.maxBuildYExclusive(),
                surface, floor, motion, topBlocks, seabedBlocks, topBiomes, topFluids, layers,
                Instant.now());
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }
}
