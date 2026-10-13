package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceKind;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceOrigin;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
import io.github.fxjcangku.worldseedlab.core.probe.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class P1ObservationSmokeTest {
    private P1ObservationSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        require(ProbeSettings.DEFAULT.verticalStride() == 8, "default vertical stride");
        expectFail(() -> new ProbeSettings(15, 4, 64), "unbounded radius");
        expectFail(() -> new ProbeSettings(4, 6, 128), "misaligned vertical stride");
        expectFail(() -> new ProbeSettings(4, 8, 9999), "unbounded tick budget");

        ChunkSamplingCursor full = new ChunkSamplingCursor(new ChunkKey(-3, 4, 4), -64, 320);
        int total = 0;
        while (full.hasNext()) {
            ChunkSamplingCursor.Sample sample = full.next();
            require(sample.x() >= -48 && sample.x() < -32, "chunk x containment");
            require(sample.z() >= 64 && sample.z() < 80, "chunk z containment");
            require(sample.y() >= -64 && sample.y() < 320, "world height containment");
            total++;
        }
        require(total == 1536, "4x4x4 quart observations per 384-block chunk");
        ChunkSamplingCursor sparse = new ChunkSamplingCursor(new ChunkKey(2, 3, 16), -64, 320);
        int sparseCount = 0;
        while (sparse.hasNext()) {
            sparse.next();
            sparseCount++;
        }
        require(sparseCount == 384, "4x4x16 sample count");

        Path folder = Files.createTempDirectory("wsl-p1-");
        try {
            String hash = "a".repeat(64);
            CompletedChunkStore store = new CompletedChunkStore(folder);
            List<EvidenceRecord> records = new ArrayList<>();
            for (int n = 0; n < 16; n++) {
                records.add(new EvidenceRecord(UUID.randomUUID(), hash, "minecraft:overworld", "26.2",
                        EvidenceKind.BIOME_OBSERVATION,
                        n % 2 == 0 ? "minecraft:plains" : "deeper_dark:azure_sanctuary",
                        (2 << 4) + (n % 4) * 4 + 2, 66, (3 << 4) + (n / 4) * 4 + 2,
                        EvidenceOrigin.CONTROLLED_TEST, Instant.now(), "controlled test"));
            }
            ChunkKey key = new ChunkKey(2, 3, 8);
            require(store.loadCompleted(hash, "minecraft:overworld").isEmpty(), "initial empty index");
            store.appendChunk(hash, "minecraft:overworld", key, records);
            require(store.loadCompleted(hash, "minecraft:overworld").contains(key), "journal persistence");
            require(Files.readAllLines(store.dataPath(hash, "minecraft:overworld")).size() == 16,
                    "all biome evidence rows must persist");
            require(store.loadCompleted(hash, "minecraft:the_nether").isEmpty(), "dimension isolation");

            EvidenceRecord manual = new EvidenceRecord(UUID.randomUUID(), hash,
                    "minecraft:overworld", "26.2", EvidenceKind.STRUCTURE_OBSERVATION,
                    "minecraft:shipwreck", 44, 65, 57, EvidenceOrigin.MANUAL_MARK,
                    Instant.now(), "manual sighting; NOT a structure origin");
            store.appendManual(manual);
            require(Files.readAllLines(store.dataPath(hash, "minecraft:overworld")).size() == 17,
                    "manual observation must append without overwriting chunk samples");
            require(store.loadCompleted(hash, "minecraft:overworld").size() == 1,
                    "manual observation must not falsely complete a chunk");

            List<EvidenceRecord> mixed = new ArrayList<>(records);
            mixed.add(new EvidenceRecord(UUID.randomUUID(), hash, "minecraft:the_nether", "26.2",
                    EvidenceKind.BIOME_OBSERVATION, "minecraft:nether_wastes",
                    34, 66, 50, EvidenceOrigin.CONTROLLED_TEST, Instant.now(), ""));
            expectFail(() -> {
                try {
                    store.appendChunk(hash, "minecraft:overworld", key, mixed);
                } catch (Exception e) {
                    throw new IllegalArgumentException(e);
                }
            }, "cross-dimensional batch must fail closed");
            require(Files.readAllLines(store.dataPath(hash, "minecraft:overworld")).size() == 17,
                    "invalid batch must not write");
            ProbeConfigFile file = new ProbeConfigFile(folder);
            file.save(new ProbeSettings(3, 4, 96));
            require(file.load().equals(new ProbeSettings(3, 4, 96)), "save/load settings");
        } finally {
            try (var stream = Files.walk(folder)) {
                for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        System.out.println("WorldSeedLab P1 observation smoke checks passed.");
    }

    private static void expectFail(Runnable operation, String label) {
        try {
            operation.run();
            throw new AssertionError(label);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void require(boolean yes, String message) {
        if (!yes) {
            throw new AssertionError(message);
        }
    }
}
