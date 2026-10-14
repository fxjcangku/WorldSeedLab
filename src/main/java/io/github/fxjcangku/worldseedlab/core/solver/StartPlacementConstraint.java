package io.github.fxjcangku.worldseedlab.core.solver;

import io.github.fxjcangku.worldseedlab.core.compatibility.StructureObservation.ChunkBounds;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 可送入种子候选求解器的结构起始位置约束。
 *
 * <p>人工目击坐标不可转换为本类型。GEOMETRY_ESTIMATE 仍属于
 * 未独立证实的候选范围；REFERENCE_FROM_CONTROL_WORLD 应仅用于
 * 已知世界种子、真值来源可核查的受控测试。
 *
 * <p>P2.9 新增离散矩形并集：possibleStartChunks 只是查询显示用的外包范围；
 * 真正判定与 SMT 约束必须使用 alternativeStartChunks，绝不能把包络中的
 * 缺口自动当作可能的起始点。
 */
public record StartPlacementConstraint(
        String worldFingerprint,
        String gameVersion,
        String dimension,
        String structureSetId,
        ChunkBounds possibleStartChunks,
        Origin origin,
        Split split,
        List<ChunkBounds> alternativeStartChunks
) {
    private static final Pattern FINGERPRINT = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public enum Origin {
        GEOMETRY_ESTIMATE,
        REFERENCE_FROM_CONTROL_WORLD
    }

    public enum Split { FIT, HOLDOUT }

    /** 兼容 P2.7/P2.8 原有单矩形起点约束，语义保持不变。 */
    public StartPlacementConstraint(
            String worldFingerprint, String gameVersion, String dimension,
            String structureSetId, ChunkBounds bounds, Origin origin, Split split
    ) {
        this(worldFingerprint, gameVersion, dimension, structureSetId, bounds,
                origin, split, List.of(Objects.requireNonNull(bounds)));
    }

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
        alternativeStartChunks = List.copyOf(Objects.requireNonNull(alternativeStartChunks));
        if (alternativeStartChunks.isEmpty() || alternativeStartChunks.size() > 8) {
            throw new IllegalArgumentException("structure start alternatives must be 1..8");
        }
        for (ChunkBounds alternative : alternativeStartChunks) {
            if (!possibleStartChunks.contains(alternative.minX(), alternative.minZ())
                    || !possibleStartChunks.contains(alternative.maxX(), alternative.maxZ())) {
                throw new IllegalArgumentException("start alternative lies outside envelope");
            }
        }
        if (origin == Origin.REFERENCE_FROM_CONTROL_WORLD
                && (!possibleStartChunks.isExact()
                    || alternativeStartChunks.size() != 1
                    || !possibleStartChunks.equals(alternativeStartChunks.getFirst()))) {
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

    /** 保留全部真实可行区块候选，而非填满不同朝向的包络缺口。 */
    public static StartPlacementConstraint inferredAlternatives(
            String world, String version, String dimension, String setId,
            List<ChunkBounds> alternatives, Split split
    ) {
        if (alternatives == null || alternatives.isEmpty() || alternatives.size() > 8) {
            throw new IllegalArgumentException("geometry alternatives must be 1..8");
        }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (ChunkBounds b : alternatives) {
            Objects.requireNonNull(b);
            minX = Math.min(minX, b.minX());
            minZ = Math.min(minZ, b.minZ());
            maxX = Math.max(maxX, b.maxX());
            maxZ = Math.max(maxZ, b.maxZ());
        }
        return new StartPlacementConstraint(world, version, dimension, setId,
                new ChunkBounds(minX, minZ, maxX, maxZ),
                Origin.GEOMETRY_ESTIMATE, split, alternatives);
    }

    /** 只用独立的有效候选矩形复核 Java RNG，避免包络缺口产生伪匹配。 */
    public boolean matchesPlacement(long low48Seed, RandomSpreadSpec spec) {
        if (!structureSetId.equals(spec.structureSetId())) {
            throw new IllegalArgumentException("structure set ID mismatch");
        }
        Set<Long> seenRegions = new HashSet<>();
        for (ChunkBounds b : alternativeStartChunks) {
            long fromX = Math.floorDiv(b.minX(), spec.spacing());
            long toX = Math.floorDiv(b.maxX(), spec.spacing());
            long fromZ = Math.floorDiv(b.minZ(), spec.spacing());
            long toZ = Math.floorDiv(b.maxZ(), spec.spacing());
            for (long rx = fromX; rx <= toX; rx++) {
                for (long rz = fromZ; rz <= toZ; rz++) {
                    long tag = (rx << 32) ^ (rz & 0xffff_ffffL);
                    if (!seenRegions.add(tag)) continue;
                    if (seenRegions.size() > 64) {
                        throw new IllegalArgumentException("candidate region too broad");
                    }
                    RandomSpreadMath.Chunk predicted =
                            RandomSpreadMath.potentialChunk(low48Seed, (int) rx, (int) rz, spec);
                    for (ChunkBounds alternative : alternativeStartChunks) {
                        if (alternative.contains(predicted.x(), predicted.z())) return true;
                    }
                }
            }
        }
        return false;
    }
}
