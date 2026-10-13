package io.github.fxjcangku.worldseedlab.core.probe;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/** Only numeric preferences are persisted. Never store sampling permission. */
public final class ProbeConfigFile {
    private final Path configFile;

    public ProbeConfigFile(Path configFolder) {
        this.configFile = configFolder.resolve("probe.properties");
    }

    public ProbeSettings load() {
        if (!Files.isRegularFile(configFile)) {
            return ProbeSettings.DEFAULT;
        }
        try {
            if (Files.size(configFile) > 4096) {
                return ProbeSettings.DEFAULT;
            }
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(configFile)) {
                properties.load(input);
            }
            return new ProbeSettings(
                    Integer.parseInt(properties.getProperty("radius_chunks", "4")),
                    Integer.parseInt(properties.getProperty("vertical_stride", "8")),
                    Integer.parseInt(properties.getProperty("samples_per_tick", "128")),
                    DetailMode.valueOf(properties.getProperty("detail_mode", "OFF"))
            );
        } catch (IOException | RuntimeException invalid) {
            return ProbeSettings.DEFAULT;
        }
    }

    public void save(ProbeSettings settings) throws IOException {
        Files.createDirectories(configFile.getParent());
        Properties properties = new Properties();
        properties.setProperty("radius_chunks", Integer.toString(settings.radiusChunks()));
        properties.setProperty("vertical_stride", Integer.toString(settings.verticalStride()));
        properties.setProperty("samples_per_tick", Integer.toString(settings.samplesPerTick()));
        properties.setProperty("detail_mode", settings.detailMode().name());
        try (OutputStream output = Files.newOutputStream(configFile, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            properties.store(output, "WorldSeedLab P1 preferences; sampling remains OFF on startup");
        }
    }
}
