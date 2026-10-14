package io.github.fxjcangku.worldseedlab.client.probe;

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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 在已加载区块对地表和海床附近进行稀疏方块扫描，单 Tick 有界读取。
 *
 * <p>16 个水平列 x 两个地形锚点 x 13 个间隔 2 方块的高度位置；
 * 同一实际方块只取一次。无法检测全部建筑，尤其地下地牢和被地形覆盖的建筑。
 * 采样中区块卸载时不保存“无结构”结论。
 */
public final class LoadedStructureScanCursor {
    private static final int[] COLUMNS = {2, 6, 10, 14};
    private static final int[] OFFSETS = {-12, -10, -8, -6, -4, -2, 0, 2, 4, 6, 8, 10, 12};
    private final LevelChunk originalChunk;
    private final int chunkX, chunkZ;
    private final List<BlockPos> points = new ArrayList<>(416);
    private final Map<String, Integer> blocks = new HashMap<>();
    private final String fingerprint, dimension, gameVersion;
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
        Set<Long> seen = new HashSet<>(512);
        int min = level.getMinY();
        int max = level.getMaxY() - 1;
        if (max <= min) throw new IllegalArgumentException("invalid build height");
        for (int localX : COLUMNS) {
            for (int localZ : COLUMNS) {
                int top = originalChunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ) - 1;
                int oceanFloor = originalChunk.getHeight(
                        Heightmap.Types.OCEAN_FLOOR, localX, localZ) - 1;
                for (int anchor : new int[] {top, oceanFloor}) {
                    for (int delta : OFFSETS) {
                        int y = Math.max(min, Math.min(max, anchor + delta));
                        long key = ((long) (localX * 16 + localZ) << 32) | (y & 0xffffffffL);
                        if (seen.add(key)) {
                            points.add(new BlockPos((chunkX << 4) + localX, y,
                                    (chunkZ << 4) + localZ));
                        }
                    }
                }
            }
        }
    }

    public boolean isLoaded(ClientLevel level) {
        return level.getChunkSource().getChunk(
                chunkX, chunkZ, ChunkStatus.FULL, false) == originalChunk;
    }

    public boolean finished() {
        return next >= points.size();
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }

    public void advance(ClientLevel level, int budget) {
        if (budget < 1 || budget > 96) {
            throw new IllegalArgumentException("structure scan tick budget out of range");
        }
        if (!isLoaded(level)) throw new IllegalStateException("chunk unloaded during scan");
        for (int i = 0; i < budget && !finished(); i++) {
            BlockPos at = points.get(next++);
            String id = BuiltInRegistries.BLOCK.getKey(
                    originalChunk.getBlockState(at).getBlock()).toString();
            // 假矿、普通矿物和矿石分布不参与任何结构种子候选。
            if (!id.contains("_ore") && !id.contains(":ore")) {
                blocks.merge(id, 1, Integer::sum);
            }
            minSampleY = Math.min(minSampleY, at.getY());
            maxSampleY = Math.max(maxSampleY, at.getY());
        }
    }

    public StructureScanEvidence finishEvidence() {
        if (!finished()) throw new IllegalStateException("partial scan cannot be persisted");
        return new StructureScanEvidence(fingerprint, dimension, gameVersion,
                chunkX, chunkZ, points.size(), minSampleY, maxSampleY,
                StructureSignatureDetector.classify(blocks, points.size()), Instant.now());
    }
}
