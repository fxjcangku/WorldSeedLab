package io.github.fxjcangku.worldseedlab.core.probe;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceJsonLines;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
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
     * 独立读取精细区块完成索引。旧 P1 群系/P2 高度图记录不会阻止补充采样。
     * 损坏日志绝不静默略过，避免把缺失证据报告成采样完成。
     */
    public Set<io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkKey>
    loadRichCompleted(String fingerprint, String dimension) throws IOException {
        Path journal = base(fingerprint, dimension).resolve("rich-completed.csv");
        Set<io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkKey> keys =
                new HashSet<>();
        if (!Files.exists(journal)) return keys;
        try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
            String line;
            int rows = 0;
            while ((line = reader.readLine()) != null) {
                if (++rows > MAX_JOURNAL_ROWS) {
                    throw new IOException("rich completion index exceeds safe limit");
                }
                try {
                    keys.add(io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkKey
                            .parse(line));
                } catch (RuntimeException bad) {
                    throw new IOException("corrupt rich completion index", bad);
                }
            }
        }
        return keys;
    }

    /**
     * 精细日志设 128 MiB 的单维度软上限，避免长时间跑图无限占用磁盘。
     * 先同步刷入完整 JSONL，再记录完成索引；异常可能重复，绝不凭空记完成。
     */
    public void appendRich(io.github.fxjcangku.worldseedlab.core.generation.RichChunkSample sample)
            throws IOException {
        Path folder = base(sample.worldFingerprint(), sample.dimension());
        Files.createDirectories(folder);
        String line = io.github.fxjcangku.worldseedlab.core.generation.RichChunkJsonLines
                .serialize(sample) + "\n";
        writeRichLimited(folder.resolve("rich-chunks.jsonl"), line);
        writeAndForce(folder.resolve("rich-completed.csv"),
                sample.chunkX() + "," + sample.chunkZ() + "\n");
    }

    private static void writeRichLimited(Path path, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65536) {
            throw new IOException("rich observation row too large");
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             FileLock lock = channel.lock()) {
            if (channel.size() + bytes.length > 128L * 1024 * 1024) {
                throw new IOException("精细日志达到单维度 128 MiB 限额，已停止取证");
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(false);
        }
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
