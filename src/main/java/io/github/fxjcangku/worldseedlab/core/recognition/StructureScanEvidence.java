package io.github.fxjcangku.worldseedlab.core.recognition;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 完整区块的稀疏方块观测；即使没有候选，也只表示本次采样规则没有发现线索。 */
public record StructureScanEvidence(
        String worldFingerprint,
        String dimension,
        String clientVersion,
        int chunkX, int chunkZ,
        int scannedCells,
        int minObservedY,
        int maxObservedY,
        List<StructureCandidate> candidates,
        Instant scannedAt
) {
    public static final int MODEL_VERSION = 3;
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public StructureScanEvidence {
        if (worldFingerprint == null || !HASH.matcher(worldFingerprint).matches()
                || dimension == null || !ID.matcher(dimension).matches()) {
            throw new IllegalArgumentException("invalid world/dimension scope");
        }
        if (!"26.1.2".equals(clientVersion) && !"26.2".equals(clientVersion)) {
            throw new IllegalArgumentException("unsupported observation game version");
        }
        if (scannedCells < 32 || scannedCells > 8192 || minObservedY > maxObservedY) {
            throw new IllegalArgumentException("invalid or incomplete structure probe coverage");
        }
        candidates = List.copyOf(Objects.requireNonNull(candidates));
        if (candidates.size() > 16) throw new IllegalArgumentException("too many candidates");
        Objects.requireNonNull(scannedAt);
    }

    public long weakCount() {
        return candidates.stream().filter(c -> c.grade() == StructureCandidate.Grade.WEAK).count();
    }
}
