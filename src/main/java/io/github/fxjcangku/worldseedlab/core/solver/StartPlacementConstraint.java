package io.github.fxjcangku.worldseedlab.core.solver;

import io.github.fxjcangku.worldseedlab.core.compatibility.StructureObservation.ChunkBounds;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 可送入种子候选求解器的结构起始位置约束。
 *
 * <p>人工目击坐标不可转换为本类型。GEOMETRY_ESTIMATE 仍属于
 * 未独立证实的候选范围；REFERENCE_FROM_CONTROL_WORLD 应仅用于
 * 已知世界种子、真值来源可核查的受控测试。
 */
public record StartPlacementConstraint(
        String worldFingerprint,
        String gameVersion,
        String dimension,
        String structureSetId,
        ChunkBounds possibleStartChunks,
        Origin origin,
        Split split
) {
    private static final Pattern FINGERPRINT = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public enum Origin {
        GEOMETRY_ESTIMATE,
        REFERENCE_FROM_CONTROL_WORLD
    }

    public enum Split { FIT, HOLDOUT }

    public StartPlacementConstraint {
        if (worldFingerprint == null || !FINGERPRINT.matcher(worldFingerprint).matches()
                || dimension == null || !ID.matcher(dimension).matches()
                || structureSetId == null || !ID.matcher(structureSetId).matches()) {
            throw new IllegalArgumentException("invalid evidence world/structure-set scope");
        }
        if (!"26.1.2".equals(gameVersion) && !"26.2".equals(gameVersion)) {
            throw new IllegalArgumentException("unsupported evidence game version");
        }
        Objects.requireNonNull(possibleStartChunks);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(split);
        if (origin == Origin.REFERENCE_FROM_CONTROL_WORLD
                && !possibleStartChunks.isExact()) {
            throw new IllegalArgumentException("controlled reference must have exact start");
        }
    }

    public static StartPlacementConstraint controlledExact(
            String world, String version, String dimension, String setId,
            int startX, int startZ, Split split
    ) {
        return new StartPlacementConstraint(world, version, dimension, setId,
                new ChunkBounds(startX, startZ, startX, startZ),
                Origin.REFERENCE_FROM_CONTROL_WORLD, split);
    }

    public static StartPlacementConstraint inferredRegion(
            String world, String version, String dimension, String setId,
            ChunkBounds bounds, Split split
    ) {
        return new StartPlacementConstraint(world, version, dimension, setId,
                bounds, Origin.GEOMETRY_ESTIMATE, split);
    }

    /** 不知道结构自然生成的起点时，不能由本类把玩家站位当成准确坐标。 */
    public boolean matchesPlacement(long low48Seed, RandomSpreadSpec spec) {
        if (!structureSetId.equals(spec.structureSetId())) {
            throw new IllegalArgumentException("structure set ID mismatch");
        }
        ChunkBounds b = possibleStartChunks;
        int fromX = Math.floorDiv(b.minX(), spec.spacing());
        int toX = Math.floorDiv(b.maxX(), spec.spacing());
        int fromZ = Math.floorDiv(b.minZ(), spec.spacing());
        int toZ = Math.floorDiv(b.maxZ(), spec.spacing());
        long regionCount = ((long) toX - fromX + 1) * ((long) toZ - fromZ + 1);
        if (regionCount > 64) {
            throw new IllegalArgumentException("candidate region too broad for bounded validation");
        }
        for (int rx = fromX; rx <= toX; rx++) {
            for (int rz = fromZ; rz <= toZ; rz++) {
                RandomSpreadMath.Chunk predicted =
                        RandomSpreadMath.potentialChunk(low48Seed, rx, rz, spec);
                if (b.contains(predicted.x(), predicted.z())) {
                    return true;
                }
            }
        }
        return false;
    }
}
