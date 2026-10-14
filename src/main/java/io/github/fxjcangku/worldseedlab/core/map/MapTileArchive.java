package io.github.fxjcangku.worldseedlab.core.map;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceKind;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceOrigin;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkSample;
import io.github.fxjcangku.worldseedlab.core.probe.ChunkKey;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 客户端已观测证据的紧凑地图索引。以区块为单位生成 gzip 小文件，
 * 写入临时文件完成后原子替换；崩溃时未完成临时文件不会被地图读取。
 *
 * <p>路径仅在现有 HMAC 世界 + 维度目录下，文件内容不包含服务器地址、种子。
 * 地图只加载视口需要的文件，不重读整个可能很大的 evidence.jsonl。
 */
public final class MapTileArchive {
    private static final int MAGIC = 0x57534C4D;
    private static final int VERSION = 1;
    private static final int BIOME = 1;
    private static final int HEIGHT = 2;
    private static final int MARKER = 3;
    // 极高自定义维度允许最多 1024 个垂直层 × 16 个水平 quart 样本。
    private static final int MAX_BIOME_SAMPLES = 16384;
    private static final int MAX_FILE = 256 * 1024;

    private MapTileArchive() {}

    public static Path root(Path scopedFolder) {
        return Objects.requireNonNull(scopedFolder).resolve("observed-map");
    }

    private static String region(int chunkX, int chunkZ) {
        return "r." + Math.floorDiv(chunkX, 32) + "." + Math.floorDiv(chunkZ, 32);
    }

    private static Path biomePath(Path root, int chunkX, int chunkZ, int stride) {
        return root.resolve("biomes").resolve(region(chunkX, chunkZ))
                .resolve("b." + chunkX + "." + chunkZ + "." + stride + ".wsl.gz");
    }

    private static Path heightPath(Path root, int chunkX, int chunkZ) {
        return root.resolve("heights").resolve(region(chunkX, chunkZ))
                .resolve("h." + chunkX + "." + chunkZ + ".wsl.gz");
    }

    private static Path markerDir(Path root, int chunkX, int chunkZ) {
        return root.resolve("markers").resolve(region(chunkX, chunkZ));
    }

    public static void writeBiomes(Path root, ChunkKey key, List<EvidenceRecord> records)
            throws IOException {
        if (records.isEmpty() || records.size() > MAX_BIOME_SAMPLES) {
            throw new IOException("invalid biome map tile size");
        }
        byte[] bytes = encode(out -> {
            header(out, BIOME);
            out.writeInt(key.chunkX());
            out.writeInt(key.chunkZ());
            out.writeInt(key.verticalStride());
            out.writeInt(records.size());
            for (EvidenceRecord e : records) {
                if (e.kind() != EvidenceKind.BIOME_OBSERVATION
                        || e.origin() != EvidenceOrigin.CLIENT_OBSERVATION
                        || Math.floorDiv(e.x(), 16) != key.chunkX()
                        || Math.floorDiv(e.z(), 16) != key.chunkZ()
                        || Math.floorMod(e.x(), 4) != 2
                        || Math.floorMod(e.z(), 4) != 2) {
                    throw new IOException("map tile contains unverified or foreign position");
                }
                out.writeByte(Math.floorMod(e.x(), 16));
                out.writeByte(Math.floorMod(e.z(), 16));
                out.writeInt(e.y());
                out.writeUTF(e.observedIdentifier());
            }
        });
        atomicWrite(biomePath(root, key.chunkX(), key.chunkZ(), key.verticalStride()), bytes);
    }

    public static void writeHeight(Path root, TerrainChunkSample terrain) throws IOException {
        byte[] bytes = encode(out -> {
            header(out, HEIGHT);
            out.writeInt(terrain.chunkX());
            out.writeInt(terrain.chunkZ());
            for (Integer h : terrain.surfaceHeights()) out.writeInt(h);
        });
        atomicWrite(heightPath(root, terrain.chunkX(), terrain.chunkZ()), bytes);
    }

    public static void writeMarker(Path root, EvidenceRecord e) throws IOException {
        if (e.kind() != EvidenceKind.STRUCTURE_OBSERVATION
                || e.origin() != EvidenceOrigin.MANUAL_MARK) {
            throw new IOException("map markers require manual unverified sightings");
        }
        byte[] bytes = encode(out -> {
            header(out, MARKER);
            out.writeInt(e.x());
            out.writeInt(e.y());
            out.writeInt(e.z());
            out.writeUTF(e.observedIdentifier());
        });
        Path dir = markerDir(root, Math.floorDiv(e.x(), 16), Math.floorDiv(e.z(), 16));
        atomicWrite(dir.resolve("m." + e.id() + ".wsl.gz"), bytes);
    }

    public static List<BiomePoint> readBiomes(
            Path root, int chunkX, int chunkZ, int stride
    ) throws IOException {
        Path path = biomePath(root, chunkX, chunkZ, stride);
        if (!Files.exists(path)) return List.of();
        try (DataInputStream in = open(path, BIOME)) {
            int x = in.readInt(), z = in.readInt(), actualStride = in.readInt();
            int count = in.readInt();
            if (x != chunkX || z != chunkZ || actualStride != stride
                    || count < 1 || count > MAX_BIOME_SAMPLES) {
                throw new IOException("biome map tile metadata mismatch");
            }
            List<BiomePoint> found = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int dx = in.readUnsignedByte(), dz = in.readUnsignedByte();
                int y = in.readInt();
                String id = in.readUTF();
                if (dx >= 16 || dz >= 16 || dx % 4 != 2 || dz % 4 != 2
                        || id.length() > 256 || !id.contains(":")) {
                    throw new IOException("invalid biome map tile observation");
                }
                found.add(new BiomePoint(chunkX * 16 + dx, y, chunkZ * 16 + dz, id));
            }
            verifyFinished(in);
            return found;
        }
    }

    public static int[] readHeights(Path root, int chunkX, int chunkZ) throws IOException {
        Path path = heightPath(root, chunkX, chunkZ);
        if (!Files.exists(path)) return null;
        try (DataInputStream in = open(path, HEIGHT)) {
            if (in.readInt() != chunkX || in.readInt() != chunkZ) {
                throw new IOException("height map tile metadata mismatch");
            }
            int[] h = new int[16];
            for (int i = 0; i < h.length; i++) h[i] = in.readInt();
            verifyFinished(in);
            return h;
        }
    }

    public static List<MapView.Marker> readMarkers(
            Path root, int firstChunkX, int firstChunkZ, int lastChunkX, int lastChunkZ
    ) throws IOException {
        List<MapView.Marker> found = new ArrayList<>();
        int fromRX = Math.floorDiv(firstChunkX, 32), toRX = Math.floorDiv(lastChunkX, 32);
        int fromRZ = Math.floorDiv(firstChunkZ, 32), toRZ = Math.floorDiv(lastChunkZ, 32);
        for (int rx = fromRX; rx <= toRX; rx++) {
            for (int rz = fromRZ; rz <= toRZ; rz++) {
                Path dir = root.resolve("markers").resolve("r." + rx + "." + rz);
                if (!Files.isDirectory(dir)) continue;
                try (var files = Files.newDirectoryStream(dir, "m.*.wsl.gz")) {
                    for (Path path : files) {
                        if (found.size() >= 2048) return found;
                        try (DataInputStream in = open(path, MARKER)) {
                            int x = in.readInt(), y = in.readInt(), z = in.readInt();
                            String id = in.readUTF();
                            verifyFinished(in);
                            int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
                            if (cx >= firstChunkX && cx <= lastChunkX
                                    && cz >= firstChunkZ && cz <= lastChunkZ) {
                                found.add(new MapView.Marker(x, y, z, id));
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    public record BiomePoint(int x, int y, int z, String identifier) {}

    @FunctionalInterface
    private interface WritePayload {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] encode(WritePayload writer) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream(4096);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(data))) {
            writer.write(out);
        }
        if (data.size() > MAX_FILE) throw new IOException("map tile exceeds size budget");
        return data.toByteArray();
    }

    private static void header(DataOutputStream out, int type) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeByte(type);
    }

    private static DataInputStream open(Path path, int expectedType) throws IOException {
        if (Files.size(path) > MAX_FILE) {
            throw new IOException("map tile exceeds safe read budget");
        }
        DataInputStream in = new DataInputStream(
                new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path))));
        try {
            if (in.readInt() != MAGIC || in.readInt() != VERSION
                    || in.readUnsignedByte() != expectedType) {
                throw new IOException("unsupported map index format");
            }
            return in;
        } catch (IOException failed) {
            in.close();
            throw failed;
        }
    }

    private static void verifyFinished(DataInputStream input) throws IOException {
        if (input.read() != -1) throw new IOException("unexpected map tile payload");
    }

    private static void atomicWrite(Path destination, byte[] bytes) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temp = Files.createTempFile(destination.getParent(), "wsl-", ".partial");
        try {
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
