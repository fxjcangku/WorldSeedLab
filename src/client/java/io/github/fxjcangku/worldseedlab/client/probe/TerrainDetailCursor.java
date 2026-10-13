package io.github.fxjcangku.worldseedlab.client.probe;

import io.github.fxjcangku.worldseedlab.core.generation.DimensionProfile;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainDetailKey;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainDetailSample;
import io.github.fxjcangku.worldseedlab.core.probe.DetailMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 主线程分 Tick 扫描器：只读已缓存 LevelChunk，不触发额外区块加载。
 *
 * <p>一轮扫描最多 16 个地表列 + 256 个垂直切片方块。只有全部读取完成，
 * 才能把结果交给异步写盘队列；中途区块卸载则丢弃该轮未完成数据。
 */
public final class TerrainDetailCursor {
    private final DimensionProfile profile;
    private final TerrainDetailKey key;
    private final LevelChunk chunk;
    private final int verticalStride;
    private final int depthLayers;
    private final List<TerrainDetailSample.SurfaceCell> surface = new ArrayList<>(16);
    private final List<TerrainDetailSample.VerticalCell> vertical = new ArrayList<>(256);
    private int cursor;

    public TerrainDetailCursor(ClientLevel level, DimensionProfile profile, TerrainDetailKey key) {
        this.profile = profile;
        this.key = key;
        this.chunk = level.getChunkSource().getChunk(
                key.chunkX(), key.chunkZ(), ChunkStatus.FULL, false);
        if (chunk == null || key.mode() == DetailMode.OFF) {
            throw new IllegalArgumentException("no loaded chunk or detail scanning disabled");
        }
        int totalHeight = profile.totalHeight();
        this.verticalStride = key.mode() == DetailMode.STRATA
                ? Math.max(16, (((totalHeight + 63) / 64) + 3) / 4 * 4) : 0;
        this.depthLayers = key.mode() == DetailMode.STRATA
                ? Math.min(64, Math.max(1, (totalHeight - 2 + verticalStride - 1) / verticalStride)) : 0;
    }

    public boolean isStillLoaded(ClientLevel level) {
        return level.getChunkSource().getChunk(
                key.chunkX(), key.chunkZ(), ChunkStatus.FULL, false) == chunk;
    }

    public boolean isComplete() {
        return cursor >= 16 + depthLayers * 4;
    }

    public int collectedBlockStates() {
        return cursor;
    }

    public TerrainDetailKey key() {
        return key;
    }

    public void advance(ClientLevel level, int budget) {
        if (budget < 1 || budget > 32) {
            throw new IllegalArgumentException("per-tick block sampling budget must be 1..32");
        }
        if (!isStillLoaded(level)) {
            throw new IllegalStateException("sampling chunk unloaded or replaced");
        }
        for (int n = 0; n < budget && !isComplete(); n++, cursor++) {
            if (cursor < 16) {
                int x = (cursor / 4) * 4 + 2;
                int z = (cursor % 4) * 4 + 2;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                int y = Math.max(profile.minBuildY(), Math.min(
                        profile.maxBuildYExclusive() - 1, top));
                BlockPos pos = new BlockPos((key.chunkX() << 4) + x, y,
                        (key.chunkZ() << 4) + z);
                BlockState state = chunk.getBlockState(pos);
                surface.add(new TerrainDetailSample.SurfaceCell(
                        x, z, y, blockId(state), truncateState(state.toString()),
                        fluidId(state), biomeId(level, pos)));
                continue;
            }
            int index = cursor - 16;
            int col = index % 4;
            int layer = index / 4;
            int x = col < 2 ? 2 : 10;
            int z = col % 2 == 0 ? 2 : 10;
            int y = profile.minBuildY() + 2 + layer * verticalStride;
            BlockPos pos = new BlockPos((key.chunkX() << 4) + x, y,
                    (key.chunkZ() << 4) + z);
            BlockState state = chunk.getBlockState(pos);
            vertical.add(new TerrainDetailSample.VerticalCell(
                    x, z, y, blockId(state), fluidId(state), biomeId(level, pos)));
        }
    }

    public TerrainDetailSample result() {
        if (!isComplete()) {
            throw new IllegalStateException("cannot persist unfinished terrain detail");
        }
        return new TerrainDetailSample(profile.worldFingerprint(), profile.dimension(),
                profile.clientVersion(), key.chunkX(), key.chunkZ(),
                key.mode(), verticalStride, surface, vertical, Instant.now());
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String fluidId(BlockState state) {
        var fluid = state.getFluidState();
        return fluid.isEmpty() ? "minecraft:empty"
                : BuiltInRegistries.FLUID.getKey(fluid.getType()).toString();
    }

    private static String biomeId(ClientLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(key -> key.identifier().toString())
                .orElseThrow(() -> new IllegalStateException("unregistered observed biome"));
    }

    private static String truncateState(String text) {
        return text.length() > 512 ? text.substring(0, 512) : text;
    }
}
