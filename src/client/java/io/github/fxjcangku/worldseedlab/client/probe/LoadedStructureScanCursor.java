package io.github.fxjcangku.worldseedlab.client.probe;

import io.github.fxjcangku.worldseedlab.core.generation.ClientHeightmapSafety;
import io.github.fxjcangku.worldseedlab.core.recognition.StructureScanEvidence;
import io.github.fxjcangku.worldseedlab.core.recognition.StructureSignatureDetector;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 已加载FULL区块的有界材料探测，始终只是非天然来源认证的结构候选。
 *
 * <p>客户端OCEAN_FLOOR可能完全未初始化，minBuildY-1是无效高度。
 * 先按16个地表列的真实FluidState预检，再按陆地/水域生成本区块
 * 的材料扫描点。海床高度可用时利用它；不可用时只采相对实际水面的
 * 有限深度带，绝不把最低基岩层伪装成海床。
 *
 * <p>预检也计入原有64 BlockState/tick读取预算，未完成区块不可写盘。
 */
public final class LoadedStructureScanCursor {
    private static final int[] LAND_COLUMNS = {2, 6, 10, 14};
    private static final int[] WATER_COLUMNS = {1, 3, 5, 7, 9, 11, 13, 15};
    private static final int[] OFFSETS = {-16, -12, -10, -8, -6, -4, -2, 0, 2, 4, 6, 8, 10, 12, 16};
    private static final int PREFLIGHT_POINTS = LAND_COLUMNS.length * LAND_COLUMNS.length;

    private final LevelChunk originalChunk;
    private final int chunkX, chunkZ;
    private final int minY, maxY;
    private final List<BlockPos> points = new ArrayList<>(2048);
    private final Set<Long> planned = new HashSet<>(2048);
    private final Map<String, Integer> blocks = new HashMap<>();
    private final String fingerprint, dimension, gameVersion;
    private boolean planPrepared;
    private boolean aquaticDetail;
    private int waterSurfaceColumns;
    private int next;
    private int minSampleY = Integer.MAX_VALUE, maxSampleY = Integer.MIN_VALUE;

    public LoadedStructureScanCursor(ClientLevel level, int chunkX, int chunkZ,
                                     String fingerprint, String dimension, String gameVersion) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.fingerprint = fingerprint;
        this.dimension = dimension;
        this.gameVersion = gameVersion;
        originalChunk = level.getChunkSource().getChunk(
                chunkX, chunkZ, ChunkStatus.FULL, false);
        if (originalChunk == null) {
            throw new IllegalArgumentException("chunk is not already loaded");
        }
        minY = level.getMinY();
        maxY = level.getMaxY() - 1;
        if (maxY <= minY) throw new IllegalArgumentException("invalid build height");
        // 不在构造阶段读取BlockState。首16格预检也由advance的tick预算支付。
        for (int localX : LAND_COLUMNS) {
            for (int localZ : LAND_COLUMNS) {
                int top = originalChunk.getHeight(
                        Heightmap.Types.WORLD_SURFACE, localX, localZ) - 1;
                appendPoint(localX, top, localZ);
            }
        }
        if (points.size() != PREFLIGHT_POINTS) {
            throw new IllegalStateException("invalid surface preflight plan");
        }
    }

    public boolean isLoaded(ClientLevel level) {
        return level.getChunkSource().getChunk(
                chunkX, chunkZ, ChunkStatus.FULL, false) == originalChunk;
    }

    public boolean finished() {
        return planPrepared && next >= points.size();
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public boolean aquaticDetail() { return aquaticDetail; }
    public int plannedCells() { return points.size(); }

    private void appendPoint(int localX, int worldY, int localZ) {
        int y = Math.max(minY, Math.min(maxY, worldY));
        long unique = ((long) (localX * 16 + localZ) << 32) | (y & 0xffffffffL);
        if (planned.add(unique)) {
            points.add(new BlockPos((chunkX << 4) + localX, y,
                    (chunkZ << 4) + localZ));
        }
    }

    private static boolean actualWater(BlockState state) {
        var fluid = state.getFluidState();
        if (fluid.isEmpty()) return false;
        var key = BuiltInRegistries.FLUID.getKey(fluid.getType());
        String id = key == null ? "" : key.toString();
        return "minecraft:water".equals(id) || "minecraft:flowing_water".equals(id);
    }

    private void preparePlan() {
        if (planPrepared) return;
        if (next < PREFLIGHT_POINTS) {
            throw new IllegalStateException("cannot infer aquatic region before surface preflight");
        }
        aquaticDetail = waterSurfaceColumns >= 4;
        int[] columns = aquaticDetail ? WATER_COLUMNS : LAND_COLUMNS;
        for (int localX : columns) {
            for (int localZ : columns) {
                int surfaceHeight = originalChunk.getHeight(
                        Heightmap.Types.WORLD_SURFACE, localX, localZ);
                int top = surfaceHeight - 1;
                int oceanFloorHeight = originalChunk.getHeight(
                        Heightmap.Types.OCEAN_FLOOR, localX, localZ);
                for (int delta : OFFSETS) {
                    appendPoint(localX, top + delta, localZ);
                }
                if (ClientHeightmapSafety.usableOceanFloor(
                        minY, surfaceHeight, oceanFloorHeight)) {
                    for (int delta : OFFSETS) {
                        appendPoint(localX, oceanFloorHeight - 1 + delta, localZ);
                    }
                } else if (aquaticDetail) {
                    // 无OCEAN_FLOOR时根据真实水面额外扫描深度带，而非假海床。
                    for (int delta : ClientHeightmapSafety.fallbackWaterDepths()) {
                        appendPoint(localX, top + delta, localZ);
                    }
                }
            }
        }
        if (points.size() > 8192) {
            throw new IllegalStateException("structure scan plan exceeds safe cell bound");
        }
        planPrepared = true;
    }

    public void advance(ClientLevel level, int budget) {
        if (budget < 1 || budget > 96) {
            throw new IllegalArgumentException("structure scan tick budget out of range");
        }
        if (!isLoaded(level)) throw new IllegalStateException("chunk unloaded during scan");
        int inspected = 0;
        while (inspected < budget) {
            if (next >= points.size()) {
                if (!planPrepared) preparePlan();
                if (next >= points.size()) break;
            }
            BlockPos at = points.get(next++);
            BlockState state = originalChunk.getBlockState(at);
            if (!planPrepared && next <= PREFLIGHT_POINTS && actualWater(state)) {
                waterSurfaceColumns++;
            }
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            // 只排除矿石证据；人工建筑、改造建筑不能升级为天然生成起点。
            if (!id.contains("_ore") && !id.contains(":ore")) {
                blocks.merge(id, 1, Integer::sum);
            }
            minSampleY = Math.min(minSampleY, at.getY());
            maxSampleY = Math.max(maxSampleY, at.getY());
            inspected++;
        }
        if (!planPrepared && next >= PREFLIGHT_POINTS) preparePlan();
    }

    public StructureScanEvidence finishEvidence() {
        if (!finished()) throw new IllegalStateException("partial scan cannot be persisted");
        return new StructureScanEvidence(fingerprint, dimension, gameVersion,
                chunkX, chunkZ, points.size(), minSampleY, maxSampleY,
                StructureSignatureDetector.classify(blocks, points.size()), Instant.now());
    }
}
