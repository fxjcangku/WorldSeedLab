package io.github.fxjcangku.worldseedlab.core.map;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceKind;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceOrigin;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
import io.github.fxjcangku.worldseedlab.core.probe.ChunkKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * P2.5 地图的完整区块三维 quart 采样瓦片。
 *
 * <p>每个高度层精确保存 4×4 个水平群系格点；没有任何未采集区域的推测数据。
 * 纵向间隔属于证据自身元数据。界面选择某一 Y 时，最多展示距它半个间距内
 * 的真实观测点，并将实际观测高度与展示高度严格区分。
 */
public final class BiomeMapTile {
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private final String worldFingerprint;
    private final String dimension;
    private final int chunkX;
    private final int chunkZ;
    private final int verticalStride;
    private final int firstObservedY;
    private final List<String> palette;
    private final short[] cells;

    public BiomeMapTile(String worldFingerprint, String dimension, int chunkX, int chunkZ,
                        int verticalStride, int firstObservedY,
                        List<String> palette, short[] cells) {
        if (worldFingerprint == null || !HASH.matcher(worldFingerprint).matches()) {
            throw new IllegalArgumentException("invalid scoped map world fingerprint");
        }
        if (dimension == null || !ID.matcher(dimension).matches()) {
            throw new IllegalArgumentException("invalid map dimension");
        }
        if (verticalStride != 4 && verticalStride != 8 && verticalStride != 16) {
            throw new IllegalArgumentException("map vertical stride must be 4, 8 or 16");
        }
        if (firstObservedY < -4096 || firstObservedY > 8192) {
            throw new IllegalArgumentException("invalid first sample Y");
        }
        if (palette == null || palette.isEmpty() || palette.size() > 4096
                || cells == null || cells.length < 16 || cells.length > 16384
                || cells.length % 16 != 0) {
            throw new IllegalArgumentException("invalid biome map tile dimensions");
        }
        for (String id : palette) {
            if (id == null || id.length() > 256 || !ID.matcher(id).matches()) {
                throw new IllegalArgumentException("invalid biome ID");
            }
        }
        for (short index : cells) {
            if (index < 0 || index >= palette.size()) {
                throw new IllegalArgumentException("invalid palette index");
            }
        }
        long maxY = (long) firstObservedY + (long) (cells.length / 16 - 1) * verticalStride;
        if (maxY > 8192) {
            throw new IllegalArgumentException("tile extends beyond supported height");
        }
        this.worldFingerprint = worldFingerprint;
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.verticalStride = verticalStride;
        this.firstObservedY = firstObservedY;
        this.palette = List.copyOf(palette);
        this.cells = cells.clone();
    }

    /** 仅由完整、同世界同维度同区块的真实群系观察构造。 */
    public static BiomeMapTile fromEvidence(ChunkKey key, List<EvidenceRecord> records) {
        if (records == null || records.size() < 16 || records.size() > 16384
                || records.size() % 16 != 0) {
            throw new IllegalArgumentException("only complete quart evidence chunks may form tiles");
        }
        EvidenceRecord initial = records.get(0);
        String world = initial.worldFingerprint();
        String dimension = initial.dimension();
        int minY = records.stream().mapToInt(EvidenceRecord::y).min().orElseThrow();
        int maxY = records.stream().mapToInt(EvidenceRecord::y).max().orElseThrow();
        long diff = (long) maxY - minY;
        if (diff % key.verticalStride() != 0 || diff < 0) {
            throw new IllegalArgumentException("irregular vertical sample interval");
        }
        long layers = diff / key.verticalStride() + 1;
        if (layers * 16 != records.size() || layers > 1024) {
            throw new IllegalArgumentException("missing or repeated quart grid sample");
        }
        short[] codes = new short[records.size()];
        Arrays.fill(codes, (short) -1);
        Map<String, Short> uniqueIds = new LinkedHashMap<>();
        long originX = (long) key.chunkX() * 16;
        long originZ = (long) key.chunkZ() * 16;
        for (EvidenceRecord record : records) {
            if (!world.equals(record.worldFingerprint())
                    || !dimension.equals(record.dimension())
                    || record.kind() != EvidenceKind.BIOME_OBSERVATION
                    || record.origin() != EvidenceOrigin.CLIENT_OBSERVATION
                    || (record.x() >> 4) != key.chunkX()
                    || (record.z() >> 4) != key.chunkZ()) {
                throw new IllegalArgumentException("mixed or non-client biome evidence");
            }
            long dx = (long) record.x() - originX;
            long dz = (long) record.z() - originZ;
            long dy = (long) record.y() - minY;
            if (dx < 2 || dx > 14 || dz < 2 || dz > 14
                    || (dx - 2) % 4 != 0 || (dz - 2) % 4 != 0
                    || dy < 0 || dy % key.verticalStride() != 0) {
                throw new IllegalArgumentException("sample off the expected biome quart grid");
            }
            int yIndex = (int) (dy / key.verticalStride());
            int index = yIndex * 16 + (int) ((dx - 2) / 4) * 4 + (int) ((dz - 2) / 4);
            if (codes[index] != -1) {
                throw new IllegalArgumentException("duplicate quart sample");
            }
            Short existing = uniqueIds.get(record.observedIdentifier());
            if (existing == null) {
                if (uniqueIds.size() >= 4096) throw new IllegalArgumentException("palette exceeds limit");
                existing = (short) uniqueIds.size();
                uniqueIds.put(record.observedIdentifier(), existing);
            }
            codes[index] = existing;
        }
        for (short index : codes) {
            if (index < 0) throw new IllegalArgumentException("incomplete quart sample");
        }
        return new BiomeMapTile(world, dimension, key.chunkX(), key.chunkZ(),
                key.verticalStride(), minY, new ArrayList<>(uniqueIds.keySet()), codes);
    }

    public Optional<Cell> atQuart(int localQuartX, int localQuartZ, int selectedY) {
        if (localQuartX < 0 || localQuartX >= 4 || localQuartZ < 0 || localQuartZ >= 4) {
            return Optional.empty();
        }
        long diff = (long) selectedY - firstObservedY;
        long layer = Math.floorDiv(diff + verticalStride / 2, verticalStride);
        if (layer < 0 || layer >= layerCount()) return Optional.empty();
        int actualY = firstObservedY + (int) layer * verticalStride;
        if (Math.abs((long) actualY - selectedY) > verticalStride / 2) {
            return Optional.empty();
        }
        int index = (int) layer * 16 + localQuartX * 4 + localQuartZ;
        return Optional.of(new Cell(palette.get(cells[index]), actualY));
    }

    public record Cell(String biomeId, int observedY) { }

    public String worldFingerprint() { return worldFingerprint; }
    public String dimension() { return dimension; }
    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public int verticalStride() { return verticalStride; }
    public int firstObservedY() { return firstObservedY; }
    public int layerCount() { return cells.length / 16; }
    public List<String> palette() { return palette; }
    /** 编码写盘时才暴露防御性副本；GUI 不访问底层数据。 */
    public short[] paletteIndices() { return cells.clone(); }
}
