package io.github.fxjcangku.worldseedlab.core.probe;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
import java.util.Objects;

/**
 * Fail-closed capture permission. Startup defaults to disabled; changing worlds
 * or dimensions automatically invalidates the previous scope authorization.
 */
public final class ProbeGate {
    private String worldFingerprint;
    private String dimension;
    private boolean active;

    public synchronized boolean isActive() {
        return active;
    }

    /** For an explicit future user action only. Currently not called by the client. */
    public synchronized void arm(String worldFingerprint, String dimension) {
        if (worldFingerprint == null || !worldFingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid world fingerprint");
        }
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("dimension is required");
        }
        this.worldFingerprint = worldFingerprint;
        this.dimension = dimension;
        this.active = true;
    }

    public synchronized boolean allows(EvidenceRecord record) {
        return active && record != null
                && Objects.equals(worldFingerprint, record.worldFingerprint())
                && Objects.equals(dimension, record.dimension());
    }

    public synchronized void onScopeChanged(String currentWorld, String currentDimension) {
        if (!Objects.equals(worldFingerprint, currentWorld)
                || !Objects.equals(dimension, currentDimension)) {
            disarm();
        }
    }

    public synchronized void disarm() {
        active = false;
        worldFingerprint = null;
        dimension = null;
    }
}

