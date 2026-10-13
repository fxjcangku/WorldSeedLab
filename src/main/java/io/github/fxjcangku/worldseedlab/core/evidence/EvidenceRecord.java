package io.github.fxjcangku.worldseedlab.core.evidence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** One observation. It never claims that a world seed has been recovered. */
public record EvidenceRecord(
        UUID id,
        String worldFingerprint,
        String dimension,
        String clientVersion,
        EvidenceKind kind,
        String observedIdentifier,
        int x,
        int y,
        int z,
        EvidenceOrigin origin,
        Instant observedAt,
        String note
) {
    private static final Pattern FINGERPRINT = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public EvidenceRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(observedAt, "observedAt");
        if (worldFingerprint == null || !FINGERPRINT.matcher(worldFingerprint).matches()) {
            throw new IllegalArgumentException("worldFingerprint must be a SHA-256 hex digest");
        }
        if (dimension == null || !RESOURCE_ID.matcher(dimension).matches()) {
            throw new IllegalArgumentException("invalid dimension resource ID");
        }
        if (observedIdentifier == null || !RESOURCE_ID.matcher(observedIdentifier).matches()) {
            throw new IllegalArgumentException("invalid observed identifier");
        }
        if (clientVersion == null || clientVersion.isBlank() || clientVersion.length() > 32) {
            throw new IllegalArgumentException("invalid client version");
        }
        note = note == null ? "" : note;
        if (note.length() > 1024) {
            throw new IllegalArgumentException("note too long");
        }
    }
}

