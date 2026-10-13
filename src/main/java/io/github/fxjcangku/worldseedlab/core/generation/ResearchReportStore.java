package io.github.fxjcangku.worldseedlab.core.generation;

import io.github.fxjcangku.worldseedlab.core.privacy.WorldFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** 将摘要原子替换为最新报告；文件名不携带服务器地址或原始世界名称。 */
public final class ResearchReportStore {
    private final Path root;

    public ResearchReportStore(Path root) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
    }

    public Path export(DimensionProfile profile, String text) throws IOException {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(text);
        if (text.length() > 100_000) {
            throw new IOException("research report exceeds safe output limit");
        }
        Path folder = root.resolve("worlds").resolve(profile.worldFingerprint())
                .resolve("dimensions").resolve(WorldFingerprint.dimensionLabel(profile.dimension()))
                .normalize();
        if (!folder.startsWith(root)) {
            throw new IOException("report path escaped data root");
        }
        Files.createDirectories(folder);
        Path destination = folder.resolve("research-report.txt");
        Path temporary = Files.createTempFile(folder, "report-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
