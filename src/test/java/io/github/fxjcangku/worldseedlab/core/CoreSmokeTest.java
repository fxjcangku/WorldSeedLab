package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceJsonLines;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceKind;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceOrigin;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceStore;
import io.github.fxjcangku.worldseedlab.core.privacy.WorldFingerprint;
import io.github.fxjcangku.worldseedlab.core.probe.ProbeGate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/** Dependency-free acceptance checks, run from Gradle's coreSmokeTest task. */
public final class CoreSmokeTest {
    private CoreSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        require(BootstrapCheck.inspect(25, "26.1.2").ok(), "26.1.2 must be supported");
        require(BootstrapCheck.inspect(25, "26.2").ok(), "26.2 must be supported");
        require(!BootstrapCheck.inspect(21, "26.2").ok(), "Java 21 must be rejected");
        require(!BootstrapCheck.inspect(25, "26.3").ok(), "unsupported Minecraft must be rejected");

        byte[] firstSecret = "local-install-key-a".getBytes(StandardCharsets.UTF_8);
        byte[] secondSecret = "local-install-key-b".getBytes(StandardCharsets.UTF_8);
        String first = WorldFingerprint.fromLocalSecret(firstSecret, "test-server-world");
        require(first.equals(WorldFingerprint.fromLocalSecret(firstSecret, "test-server-world")),
                "fingerprint must be stable per installation");
        require(!first.equals(WorldFingerprint.fromLocalSecret(secondSecret, "test-server-world")),
                "fingerprints must be isolated across different local secrets");
        require(first.matches("[a-f0-9]{64}"), "fingerprint must be SHA-256 hex");

        EvidenceRecord record = new EvidenceRecord(
                UUID.randomUUID(), first, "minecraft:overworld", "26.2",
                EvidenceKind.BIOME_OBSERVATION, "minecraft:plains",
                120, 66, -400, EvidenceOrigin.CONTROLLED_TEST,
                Instant.parse("2026-10-14T00:00:00Z"), "line one\nline two"
        );
        String json = EvidenceJsonLines.serialize(record);
        require(json.contains("\"schema\":1"), "schema version required");
        require(json.contains("\"minecraft:plains\""), "observation missing");
        require(json.contains("line one\\nline two"), "note must escape newlines");
        require(!json.contains("line one\nline two"), "raw newline must not be present");

        ProbeGate gate = new ProbeGate();
        require(!gate.isActive() && !gate.allows(record), "probe must default to disabled");
        gate.arm(first, "minecraft:overworld");
        require(gate.allows(record), "armed world must allow matching evidence");
        gate.onScopeChanged(first, "minecraft:the_nether");
        require(!gate.isActive() && !gate.allows(record), "changing dimensions must disarm");

        Path directory = Files.createTempDirectory("wsl-smoke-");
        try {
            EvidenceStore store = new EvidenceStore(directory);
            Path path = store.append(record);
            require(path.startsWith(directory), "storage must remain in root");
            require(!path.toString().contains("test-server-world"),
                    "storage path must not reveal raw server identity");
            require(Files.readAllLines(path, StandardCharsets.UTF_8).size() == 1,
                    "one evidence item must produce exactly one line");
            store.append(record);
            require(Files.readAllLines(path, StandardCharsets.UTF_8).size() == 2,
                    "append must retain existing evidence");
        } finally {
            try (var files = Files.walk(directory)) {
                files.sorted(Comparator.reverseOrder()).forEach(file -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (Exception e) {
                        throw new IllegalStateException("cleanup failed: " + file, e);
                    }
                });
            }
        }
        System.out.println("WorldSeedLab core smoke checks passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

