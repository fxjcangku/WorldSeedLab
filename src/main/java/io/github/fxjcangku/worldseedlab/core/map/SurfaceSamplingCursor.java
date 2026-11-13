package io.github.fxjcangku.worldseedlab.core.map;

import io.github.fxjcangku.worldseedlab.core.generation.DimensionProfile;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 有界逐方块扫描：每步最多一次高度图、一次 BlockState 读取；透明地图色向下寻找。
 * 游标跨 Tick 保留进度，任何卸载都拒绝导出部分区块，不为透明或未知位置补色。
 */
public final class SurfaceSamplingCursor {
    private final DimensionProfile profile;
    private final TerrainChunkKey key;
    private final Reader reader;
    private final List<Integer> heights = new ArrayList<>(256);
    private final List<Integer> colors = new ArrayList<>(256);
    private int column;
    private int nextY;
    private boolean columnStarted;

    public SurfaceSamplingCursor(DimensionProfile profile, TerrainChunkKey key, Reader reader) {
        this.profile = profile;
        this.key = key;
        this.reader = reader;
        if (!reader.stillLoaded()) throw new IllegalArgumentException("surface chunk is not loaded FULL");
    }

    public boolean isComplete() { return column == 256; }
    public boolean isStillLoaded() { return reader.stillLoaded(); }
    public TerrainChunkKey key() { return key; }
    public int completedColumns() { return column; }

    public void advance(int budget) {
        if (budget < 1 || budget > 64) throw new IllegalArgumentException("surface read budget must be 1..64");
        if (!isStillLoaded()) throw new IllegalStateException("surface chunk unloaded or replaced");
        for (int step = 0; step < budget && !isComplete(); step++) {
            int x = column / 16, z = column % 16;
            if (!columnStarted) {
                nextY = reader.surfaceY(x, z);
                if (nextY < profile.minBuildY() - 1 || nextY >= profile.maxBuildYExclusive()) {
                    throw new IllegalStateException("surface height outside observed dimension");
                }
                columnStarted = true;
            }
            if (nextY < profile.minBuildY()) {
                finishColumn(profile.minBuildY() - 1, 0);
                continue;
            }
            int color = reader.argb(x, nextY, z);
            if (color == 0) {
                nextY--;
            } else {
                if ((color >>> 24) != 255) throw new IllegalStateException("invalid native map color");
                finishColumn(nextY, color);
            }
        }
    }

    private void finishColumn(int y, int color) {
        heights.add(y);
        colors.add(color);
        column++;
        columnStarted = false;
    }

    public SurfaceChunkSample result() {
        if (!isComplete() || !isStillLoaded()) {
            throw new IllegalStateException("cannot persist partial or unloaded surface map");
        }
        return new SurfaceChunkSample(profile.worldFingerprint(), profile.dimension(),
                profile.clientVersion(), key.x(), key.z(), profile.minBuildY(),
                profile.maxBuildYExclusive(), heights, colors, Instant.now());
    }

    /** 客户端实现必须只读取同一个已加载 FULL 区块，不触发区块加载。 */
    public interface Reader {
        boolean stillLoaded();
        int surfaceY(int localX, int localZ);
        int argb(int localX, int y, int localZ);
    }
}
