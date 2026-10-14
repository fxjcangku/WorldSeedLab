package io.github.fxjcangku.worldseedlab.core.map;

import io.github.fxjcangku.worldseedlab.core.privacy.WorldFingerprint;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Objects;

/**
 * 地图瓦片与原始 evidence.jsonl 分开存储。
 *
 * <p>按 32×32 区块区域分目录，避免单个目录不断堆积文件。每瓦片单文件、
 * 原子替换，打开地图只读取可见瓦片，无需从头解析整个世界的原始日志。
 * 地图本身保留真实坐标和群系信息，分享时应按敏感世界数据对待。
 */
public final class BiomeMapTileStore {
    private static final int MAGIC = 0x57534C4D; // WSLM
    private static final int FORMAT = 1;
    private static final long MAX_TILE_BYTES = 96 * 1024;
    private final Path root;

    public BiomeMapTileStore(Path observationRoot) {
        this.root = Objects.requireNonNull(observationRoot).toAbsolutePath().normalize();
    }

    public Path path(String fingerprint, String dimension, int chunkX, int chunkZ, int stride) {
        if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid map scope");
        }
        if (stride != 4 && stride != 8 && stride != 16) {
            throw new IllegalArgumentException("unsupported tile stride");
        }
        String dimensionHash = WorldFingerprint.dimensionLabel(dimension);
        Path result = root.resolve("worlds").resolve(fingerprint)
                .resolve("dimensions").resolve(dimensionHash).resolve("biome-map")
                .resolve("region_" + Math.floorDiv(chunkX, 32) + "_" + Math.floorDiv(chunkZ, 32))
                .resolve("chunk_" + chunkX + "_" + chunkZ + "_" + stride + ".wslm")
                .normalize();
        if (!result.startsWith(root)) {
            throw new IllegalArgumentException("map path escaped world storage scope");
        }
        return result;
    }

    /** 后台磁盘工作线程调用；必须先于对应 completed-chunks.csv 的更新。 */
    public void write(BiomeMapTile tile) throws IOException {
        Path target = path(tile.worldFingerprint(), tile.dimension(),
                tile.chunkX(), tile.chunkZ(), tile.verticalStride());
        Files.createDirectories(target.getParent());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT);
            output.writeUTF(tile.worldFingerprint());
            output.writeUTF(tile.dimension());
            output.writeInt(tile.chunkX());
            output.writeInt(tile.chunkZ());
            output.writeInt(tile.verticalStride());
            output.writeInt(tile.firstObservedY());
            output.writeInt(tile.layerCount());
            output.writeInt(tile.palette().size());
            for (String id : tile.palette()) {
                output.writeUTF(id);
            }
            for (short index : tile.paletteIndices()) {
                output.writeShort(index);
            }
        }
        if (bytes.size() > MAX_TILE_BYTES) {
            throw new IOException("biome map tile exceeds safe file limit");
        }
        Path temp = Files.createTempFile(target.getParent(), "map-", ".tmp");
        try {
            Files.write(temp, bytes.toByteArray(), StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** 读取不信任任何外部本地文件，错作用域或损坏瓦片直接拒绝。 */
    public Optional<BiomeMapTile> read(String fingerprint, String dimension,
                                       int chunkX, int chunkZ, int stride) throws IOException {
        Path file = path(fingerprint, dimension, chunkX, chunkZ, stride);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file) || Files.size(file) > MAX_TILE_BYTES) {
            throw new IOException("unsafe biome tile file");
        }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT) {
                throw new IOException("unsupported biome map file");
            }
            String fileFp = input.readUTF();
            String fileDimension = input.readUTF();
            int fileX = input.readInt(), fileZ = input.readInt();
            int fileStride = input.readInt(), minY = input.readInt();
            int layers = input.readInt(), paletteSize = input.readInt();
            if (!fingerprint.equals(fileFp) || !dimension.equals(fileDimension)
                    || chunkX != fileX || chunkZ != fileZ || stride != fileStride
                    || layers < 1 || layers > 1024 || paletteSize < 1 || paletteSize > 4096
                    || (long) layers * 16 > 16384) {
                throw new IOException("map tile header does not match requested scope");
            }
            var palette = new ArrayList<String>(paletteSize);
            for (int n = 0; n < paletteSize; n++) palette.add(input.readUTF());
            short[] samples = new short[layers * 16];
            for (int n = 0; n < samples.length; n++) samples[n] = input.readShort();
            if (input.read() != -1) throw new IOException("trailing unexpected tile bytes");
            return Optional.of(new BiomeMapTile(fileFp, fileDimension, fileX, fileZ,
                    fileStride, minY, palette, samples));
        } catch (RuntimeException malformed) {
            throw new IOException("corrupted biome tile or unsupported palette", malformed);
        }
    }

    /** 同一区块若有多种精度，选择最密的真实采样数据，不插值。 */
    public Optional<BiomeMapTile> readBest(String fingerprint, String dimension,
                                           int chunkX, int chunkZ) throws IOException {
        for (int stride : new int[] {4, 8, 16}) {
            Optional<BiomeMapTile> found = read(fingerprint, dimension, chunkX, chunkZ, stride);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }
}
