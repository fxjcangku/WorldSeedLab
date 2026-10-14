package io.github.fxjcangku.worldseedlab.core.probe;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceJsonLines;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
import io.github.fxjcangku.worldseedlab.core.map.MapTileArchive;
import io.github.fxjcangku.worldseedlab.core.privacy.WorldFingerprint;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * P1/P2 分离的证据日志和区块完成索引。
 * 先强制写入证据，再记录完成状态；异常断电时可出现少量重复，
 * 但绝不把未写入成功的区块静默标记为完成。
 */
public final class CompletedChunkStore {
    private static final int MAX_JOURNAL_ROWS = 500_000;
    private final Path root;

    public CompletedChunkStore(Path dataRoot) {
        this.root = Objects.requireNonNull(dataRoot).toAbsolutePath().normalize();
    }

    public Set<ChunkKey> loadCompleted(String worldFingerprint, String dimension) throws IOException {
        Path journal = base(worldFingerprint, dimension).resolve("completed-chunks.csv");
        if (!Files.exists(journal)) {
            return new HashSet<>();
        }
        Set<ChunkKey> result = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
            String line;
            int rows = 0;
            while ((line = reader.readLine()) != null) {
                if (++rows > MAX_JOURNAL_ROWS) {
                    throw new IOException("completed chunk index exceeds the safe limit");
                }
                try {
                    result.add(ChunkKey.parseJournalLine(line));
                } catch (RuntimeException invalid) {
                    throw new IOException("invalid completed chunk index; refusing ambiguous resume", invalid);
                }
            }
        }
        return result;
    }

    public void appendChunk(
            String fingerprint, String dimension, ChunkKey key, List<EvidenceRecord> records
    ) throws IOException {
        if (records.isEmpty() || records.size() > 16384) {
            throw new IOException("invalid chunk batch size");
        }
        for (EvidenceRecord record : records) {
            if (!record.worldFingerprint().equals(fingerprint)
                    || !record.dimension().equals(dimension)
                    || record.x() >> 4 != key.chunkX()
                    || record.z() >> 4 != key.chunkZ()) {
                throw new IOException("mixed-scope chunk evidence rejected");
            }
        }

        Path folder = base(fingerprint, dimension);
        Files.createDirectories(folder);
        StringBuilder data = new StringBuilder(records.size() * 260);
        for (EvidenceRecord record : records) {
            data.append(EvidenceJsonLines.serialize(record)).append('\n');
        }

        writeAndForce(folder.resolve("evidence.jsonl"), data.toString());
        // 在完成进度之前原子生成地图索引；任何失败都不得宣称区块采集成功。
        // 仅真正的客户端观测写入“真实地图”；受控合成测试不可以画成世界真相。
        if (records.stream().allMatch(item ->
                item.origin() == io.github.fxjcangku.worldseedlab.core.evidence.EvidenceOrigin.CLIENT_OBSERVATION)) {
            MapTileArchive.writeBiomes(MapTileArchive.root(folder), key, records);
        }
        writeAndForce(folder.resolve("completed-chunks.csv"), key.asJournalLine() + "\n");
    }

    /** Manual structure sightings are not chunk-completion evidence. */
    public void appendManual(EvidenceRecord record) throws IOException {
        if (record.kind() != io.github.fxjcangku.worldseedlab.core.evidence.EvidenceKind.STRUCTURE_OBSERVATION
                || record.origin() != io.github.fxjcangku.worldseedlab.core.evidence.EvidenceOrigin.MANUAL_MARK) {
            throw new IOException("manual channel only accepts unverified structure sightings");
        }
        Path folder = base(record.worldFingerprint(), record.dimension());
        Files.createDirectories(folder);
        writeAndForce(folder.resolve("evidence.jsonl"),
                EvidenceJsonLines.serialize(record) + "\n");
        MapTileArchive.writeMarker(MapTileArchive.root(folder), record);
    }

    /**
     * P2 维度说明独立于区块完成索引；一个维度可以保留多次会话观察快照。
     */
    public void appendDimensionProfile(
            io.github.fxjcangku.worldseedlab.core.generation.DimensionProfile profile
    ) throws IOException {
        Path folder = base(profile.worldFingerprint(), profile.dimension());
        Files.createDirectories(folder);
        writeAndForce(folder.resolve("dimension-profile.jsonl"),
                io.github.fxjcangku.worldseedlab.core.generation.GenerationJsonLines.profile(profile)
                        + "\n");
    }

    /** 服务器公布的维度键使用独立日志，不能当作用户已到达的维度。 */
    public void appendAdvertisedDimensions(
            io.github.fxjcangku.worldseedlab.core.generation.AdvertisedDimensions snapshot
    ) throws IOException {
        Path folder = base(snapshot.worldFingerprint(), snapshot.currentDimension());
        Files.createDirectories(folder);
        writeAndForce(folder.resolve("advertised-dimensions.jsonl"),
                io.github.fxjcangku.worldseedlab.core.generation.GenerationJsonLines
                        .advertisedDimensions(snapshot) + "\n");
    }

    /** P2 地形采样与 P1 群系进度分别记账，保证旧数据无需重扫群系。 */
    public void appendTerrain(
            io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkSample terrain
    ) throws IOException {
        Path folder = base(terrain.worldFingerprint(), terrain.dimension());
        Files.createDirectories(folder);
        writeAndForce(folder.resolve("terrain.jsonl"),
                io.github.fxjcangku.worldseedlab.core.generation.GenerationJsonLines.terrain(terrain)
                        + "\n");
        MapTileArchive.writeHeight(MapTileArchive.root(folder), terrain);
        writeAndForce(folder.resolve("terrain-completed.csv"),
                terrain.chunkX() + "," + terrain.chunkZ() + "\n");
    }

    /** 从独立地形完成索引恢复，避免新阶段漏掉 P1 已扫过的区块。 */
    public Set<io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkKey>
    loadTerrainCompleted(String fingerprint, String dimension) throws IOException {
        Path journal = base(fingerprint, dimension).resolve("terrain-completed.csv");
        Set<io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkKey> result =
                new HashSet<>();
        if (!Files.exists(journal)) return result;
        try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
            String line;
            int rows = 0;
            while ((line = reader.readLine()) != null) {
                if (++rows > MAX_JOURNAL_ROWS) {
                    throw new IOException("terrain-completed index exceeds safe limit");
                }
                try {
                    result.add(io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkKey
                            .parse(line));
                } catch (RuntimeException invalid) {
                    throw new IOException("terrain-completed index is corrupted", invalid);
                }
            }
        }
        return result;
    }

    /**
     * 独立保存方块/流体详情：先写证据再写完成索引。
     * 升级扫描档位不会将旧地形高度索引误认为已完成高级扫描。
     */
    public void appendDetail(
            io.github.fxjcangku.worldseedlab.core.generation.TerrainDetailSample detail
    ) throws IOException {
        Path folder = base(detail.worldFingerprint(), detail.dimension());
        Files.createDirectories(folder);
        writeDetailWithLimit(folder.resolve("terrain-detail.jsonl"),
                io.github.fxjcangku.worldseedlab.core.generation.TerrainDetailJsonLines
                        .serialize(detail) + "\n");
        writeAndForce(folder.resolve("terrain-detail-completed.csv"),
                detail.key().asLine() + "\n");
    }

    /** 加载之前持久化完成的细节区块，严格隔离维度与采样档位。 */
    public Set<io.github.fxjcangku.worldseedlab.core.generation.TerrainDetailKey>
    loadDetailCompleted(String fingerprint, String dimension) throws IOException {
        Path journal = base(fingerprint, dimension).resolve("terrain-detail-completed.csv");
        Set<io.github.fxjcangku.worldseedlab.core.generation.TerrainDetailKey> result =
                new HashSet<>();
        if (!Files.exists(journal)) return result;
        try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
            String line;
            int rows = 0;
            while ((line = reader.readLine()) != null) {
                if (++rows > MAX_JOURNAL_ROWS) {
                    throw new IOException("detail journal exceeds safe row limit");
                }
                try {
                    result.add(io.github.fxjcangku.worldseedlab.core.generation.TerrainDetailKey.parse(line));
                } catch (RuntimeException corrupt) {
                    throw new IOException("corrupted detail index, refusing ambiguous resume", corrupt);
                }
            }
        }
        return result;
    }


    /**
     * 同一版本的结构稀疏扫描区块完成日志。旧版算法的“未发现”不能阻止
     * 将来新模型重新扫描；因此索引文件包含模型版本 v1。
     */
    public Set<Long> loadStructureScanned(String fingerprint, String dimension)
            throws IOException {
        Path file = base(fingerprint, dimension).resolve("structure-scanned-v1.csv");
        Set<Long> finished = new HashSet<>();
        if (!Files.exists(file)) return finished;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String row;
            int count = 0;
            while ((row = reader.readLine()) != null) {
                if (++count > MAX_JOURNAL_ROWS || row.length() > 64) {
                    throw new IOException("structure index exceeds safe bounds");
                }
                try {
                    String[] parts = row.split(",", -1);
                    if (parts.length != 2) throw new IllegalArgumentException("columns");
                    int x = Integer.parseInt(parts[0]);
                    int z = Integer.parseInt(parts[1]);
                    finished.add(packChunk(x, z));
                } catch (RuntimeException invalid) {
                    throw new IOException("invalid v1 structure scan journal", invalid);
                }
            }
        }
        return finished;
    }

    /** 仅是材料扫描完成，不是“证明此处没有自然生成结构”。 */
    public void appendStructureScan(
            io.github.fxjcangku.worldseedlab.core.recognition.StructureScanEvidence scan
    ) throws IOException {
        Path folder = base(scan.worldFingerprint(), scan.dimension());
        Files.createDirectories(folder);
        if (!scan.candidates().isEmpty()) {
            writeDetailWithLimit(folder.resolve("structure-candidates-v1.jsonl"),
                    io.github.fxjcangku.worldseedlab.core.recognition.StructureEvidenceJson
                            .line(scan) + "\n");
        }
        // 当扫描产出候选时，必须先写入审计证据，最后再记录完成索引。
        writeAndForce(folder.resolve("structure-scanned-v1.csv"),
                scan.chunkX() + "," + scan.chunkZ() + "\n");
    }

    private static long packChunk(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /** 地图只读取本地已完成证据索引，路径不携带明文服务器地址。 */
    public Path scopedFolder(String fingerprint, String dimension) {
        return base(fingerprint, dimension);
    }

    public Path dataPath(String fingerprint, String dimension) {
        return base(fingerprint, dimension).resolve("evidence.jsonl");
    }

    private Path base(String fingerprint, String dimension) {
        if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid local world fingerprint");
        }
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("missing dimension");
        }
        Path result = root.resolve("worlds").resolve(fingerprint)
                .resolve("dimensions").resolve(WorldFingerprint.dimensionLabel(dimension))
                .normalize();
        if (!result.startsWith(root)) {
            throw new IllegalArgumentException("storage path outside data root");
        }
        return result;
    }

    /**
     * 详细证据限定每维度文件最大 256 MiB，单条记录不超过 256 KiB。
     * 检查文件容量时持有同一写入锁；空间不足直接报错，不写入完成索引。
     */
    private static void writeDetailWithLimit(Path file, String record) throws IOException {
        byte[] bytes = record.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 256 * 1024) {
            throw new IOException("单个详细区块记录超出 256 KiB");
        }
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             FileLock ignored = channel.lock()) {
            if (channel.size() + bytes.length > 256L * 1024 * 1024) {
                throw new IOException("详细证据达到单维度 256 MiB 限额，请备份后再继续跑图");
            }
            ByteBuffer data = ByteBuffer.wrap(bytes);
            while (data.hasRemaining()) {
                channel.write(data);
            }
            channel.force(false);
        }
    }

    private static void writeAndForce(Path path, String data) throws IOException {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             FileLock ignored = channel.lock()) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(false);
        }
    }
}
