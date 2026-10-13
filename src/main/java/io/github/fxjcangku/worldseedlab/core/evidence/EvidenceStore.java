package io.github.fxjcangku.worldseedlab.core.evidence;

import io.github.fxjcangku.worldseedlab.core.privacy.WorldFingerprint;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Local append-only output. Never invoked automatically by client bootstrap.
 * Per-world and per-dimension paths are derived from hashes, not remote addresses.
 */
public final class EvidenceStore {
    private final Path root;

    public EvidenceStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path append(EvidenceRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        Path output = root.resolve("worlds")
                .resolve(record.worldFingerprint())
                .resolve("dimensions")
                .resolve(WorldFingerprint.dimensionLabel(record.dimension()))
                .resolve("evidence.jsonl")
                .normalize();
        if (!output.startsWith(root)) {
            throw new IOException("Evidence storage path escapes its root");
        }
        Files.createDirectories(output.getParent());

        byte[] bytes = (EvidenceJsonLines.serialize(record) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(output, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             FileLock lock = channel.lock()) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(false);
        }
        return output;
    }
}

