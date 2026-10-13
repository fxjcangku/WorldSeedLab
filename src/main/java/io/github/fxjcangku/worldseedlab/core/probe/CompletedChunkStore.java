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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Append-only evidence and a durable completed-chunk journal. Evidence is forced
 * to disk before a chunk is marked complete. A crash between both writes may
 * repeat an unfinished chunk, but never silently marks unwritten evidence done.
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
