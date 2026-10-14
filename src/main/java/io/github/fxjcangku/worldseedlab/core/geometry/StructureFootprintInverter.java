package io.github.fxjcangku.worldseedlab.core.geometry;

import io.github.fxjcangku.worldseedlab.core.compatibility.StructureObservation.ChunkBounds;
import io.github.fxjcangku.worldseedlab.core.solver.StartPlacementConstraint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P2.9 结构轮廓的保守逆向：用已知真实起点校准的相对轮廓，
 * 将目标建筑完整外边界反算为有限组“可能起始区块”。
 *
 * <p>不同朝向产生的是离散候选矩形的并集，不能直接将外包矩形
 * 中的所有空白位置当成有效候选。此类不负责证明目标建筑自然生成，
 * 也不能根据 P2.6 材料簇或玩家站位合成完整外轮廓。
 */
public final class StructureFootprintInverter {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern WORLD = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern REFERENCE = Pattern.compile("[a-zA-Z0-9_.-]{1,96}");
    private static final int MAX_CALIBRATIONS = 8;
    private static final int MAX_CANDIDATES = 256;

    private StructureFootprintInverter() {}

    /** 水平方块坐标的闭区间；不包含 Y，因为随机放置先决定 X/Z 起始区块。 */
    public record BlockBounds(int minX, int minZ, int maxX, int maxZ) {
        public BlockBounds {
            if (minX > maxX || minZ > maxZ
                    || (long) maxX - minX > 1024 || (long) maxZ - minZ > 1024) {
                throw new IllegalArgumentException("建筑轮廓颠倒或超过 1024 方块取证上限");
            }
        }
    }

    public enum FootprintQuality {
        COMPLETE_PERIMETER, PARTIAL_OR_OCCLUDED, MATERIAL_CLUSTER_ONLY
    }

    /** 完整轮廓必须由独立人工/测绘证据确认；普通材料识别不得升级。 */
    public record ObservedFootprint(
            String worldFingerprint, String gameVersion, String dimension,
            String structureSetId, String structureId, BlockBounds blocks, FootprintQuality quality
    ) {
        public ObservedFootprint {
            if (worldFingerprint == null || !WORLD.matcher(worldFingerprint).matches()
                    || !supported(gameVersion) || !validId(dimension)
                    || !validId(structureSetId) || !validId(structureId)) {
                throw new IllegalArgumentException("轮廓的世界、版本或结构集合身份非法");
            }
            Objects.requireNonNull(blocks);
            Objects.requireNonNull(quality);
        }
    }

    /**
     * 一个受控世界中“真实 StructureStart 区块 + 完整轮廓”的朝向校准。
     * referenceId 只是实验记录索引；此类型不能自行证明研究者填写的起点真实。
     */
    public record Calibration(
            String referenceId, String gameVersion, String dimension, String structureSetId,
            String structureId, String variant, int relativeMinX, int relativeMinZ,
            int relativeMaxX, int relativeMaxZ, int edgeToleranceBlocks
    ) {
        public Calibration {
            if (referenceId == null || !REFERENCE.matcher(referenceId).matches()
                    || variant == null || !REFERENCE.matcher(variant).matches()
                    || !supported(gameVersion) || !validId(dimension)
                    || !validId(structureSetId) || !validId(structureId)) {
                throw new IllegalArgumentException("校准来源、版本或结构集合非法");
            }
            if (relativeMinX > relativeMaxX || relativeMinZ > relativeMaxZ
                    || !offsetInRange(relativeMinX) || !offsetInRange(relativeMinZ)
                    || !offsetInRange(relativeMaxX) || !offsetInRange(relativeMaxZ)
                    || edgeToleranceBlocks < 0 || edgeToleranceBlocks > 32) {
                throw new IllegalArgumentException("朝向校准超出偏移/误差预算");
            }
        }

        /** 用真实已知起点区块反算边界偏移，使用 long 避免负极限坐标溢出。 */
        public static Calibration fromControlled(
                String referenceId, String version, String dimension, String setId,
                String structureId, String variant, BlockBounds controlledBoundary,
                int trueStartChunkX, int trueStartChunkZ, int tolerance
        ) {
            Objects.requireNonNull(controlledBoundary);
            long anchorX = (long) trueStartChunkX * 16;
            long anchorZ = (long) trueStartChunkZ * 16;
            return new Calibration(referenceId, version, dimension, setId, structureId, variant,
                    exactOffset((long) controlledBoundary.minX() - anchorX),
                    exactOffset((long) controlledBoundary.minZ() - anchorZ),
                    exactOffset((long) controlledBoundary.maxX() - anchorX),
                    exactOffset((long) controlledBoundary.maxZ() - anchorZ), tolerance);
        }
    }

    /** 每种朝向保留独立矩形，数量限制由 Solver 与本层共同执行。 */
    public record Inference(
            List<ChunkBounds> alternatives, ChunkBounds envelope,
            int possibleChunkCount, int envelopeChunkCount, int rejectedVariants
    ) {
        public Inference {
            alternatives = List.copyOf(alternatives);
            if (alternatives.isEmpty() || possibleChunkCount < 1
                    || envelopeChunkCount < possibleChunkCount) {
                throw new IllegalArgumentException("无合法建筑起点假设");
            }
        }

        public boolean envelopeContainsFalsePositions() {
            return envelopeChunkCount > possibleChunkCount;
        }

        public StartPlacementConstraint toConstraint(
                ObservedFootprint observed, StartPlacementConstraint.Split split
        ) {
            return StartPlacementConstraint.inferredAlternatives(
                    observed.worldFingerprint(), observed.gameVersion(),
                    observed.dimension(), observed.structureSetId(), alternatives, split);
        }
    }

    public static Inference infer(
            ObservedFootprint observed, List<Calibration> controlledCalibrations
    ) {
        Objects.requireNonNull(observed);
        Objects.requireNonNull(controlledCalibrations);
        if (observed.quality() != FootprintQuality.COMPLETE_PERIMETER) {
            throw new IllegalArgumentException("局部轮廓/材料簇不足以推断结构起始区块");
        }
        if (controlledCalibrations.isEmpty() || controlledCalibrations.size() > MAX_CALIBRATIONS) {
            throw new IllegalArgumentException("每个轮廓须提供 1..8 组受控朝向校准");
        }
        List<ChunkBounds> variants = new ArrayList<>();
        Set<String> variantIds = new HashSet<>();
        int rejected = 0;
        for (Calibration template : controlledCalibrations) {
            if (!observed.gameVersion().equals(template.gameVersion())
                    || !observed.dimension().equals(template.dimension())
                    || !observed.structureSetId().equals(template.structureSetId())
                    || !observed.structureId().equals(template.structureId())) {
                throw new IllegalArgumentException("跨版本、维度或结构集合的校准禁止混用");
            }
            if (!variantIds.add(template.variant())) {
                throw new IllegalArgumentException("重复的朝向标识不得冒充独立校准");
            }
            ChunkBounds candidate = invertVariant(observed.blocks(), template);
            if (candidate == null) {
                rejected++;
            } else if (!variants.contains(candidate)) {
                variants.add(candidate);
            }
        }
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("完整轮廓与全部受控校准矛盾，拒绝伪造起点");
        }

        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Set<Long> unique = new LinkedHashSet<>();
        for (ChunkBounds bounds : variants) {
            minX = Math.min(minX, bounds.minX());
            minZ = Math.min(minZ, bounds.minZ());
            maxX = Math.max(maxX, bounds.maxX());
            maxZ = Math.max(maxZ, bounds.maxZ());
            for (long x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (long z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    unique.add((x << 32) ^ (z & 0xffff_ffffL));
                    if (unique.size() > MAX_CANDIDATES) {
                        throw new IllegalArgumentException("轮廓起点假设超过 256，要求更精确测绘");
                    }
                }
            }
        }
        ChunkBounds envelope = new ChunkBounds(minX, minZ, maxX, maxZ);
        long envelopeCount = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
        if (envelopeCount > 4096) {
            throw new IllegalArgumentException("不同朝向跨度过大，拒绝构造不可靠包络");
        }
        return new Inference(variants, envelope, unique.size(), (int) envelopeCount, rejected);
    }

    private static ChunkBounds invertVariant(BlockBounds observed, Calibration template) {
        int tolerance = template.edgeToleranceBlocks();
        long xLow = Math.max((long) observed.minX() - template.relativeMinX() - tolerance,
                (long) observed.maxX() - template.relativeMaxX() - tolerance);
        long xHigh = Math.min((long) observed.minX() - template.relativeMinX() + tolerance,
                (long) observed.maxX() - template.relativeMaxX() + tolerance);
        long zLow = Math.max((long) observed.minZ() - template.relativeMinZ() - tolerance,
                (long) observed.maxZ() - template.relativeMaxZ() - tolerance);
        long zHigh = Math.min((long) observed.minZ() - template.relativeMinZ() + tolerance,
                (long) observed.maxZ() - template.relativeMaxZ() + tolerance);
        if (xLow > xHigh || zLow > zHigh) return null;
        long minChunkX = Math.ceilDiv(xLow, 16L);
        long maxChunkX = Math.floorDiv(xHigh, 16L);
        long minChunkZ = Math.ceilDiv(zLow, 16L);
        long maxChunkZ = Math.floorDiv(zHigh, 16L);
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) return null;
        if (maxChunkX - minChunkX > 64 || maxChunkZ - minChunkZ > 64) {
            throw new IllegalArgumentException("单一校准推断过宽");
        }
        return new ChunkBounds(Math.toIntExact(minChunkX), Math.toIntExact(minChunkZ),
                Math.toIntExact(maxChunkX), Math.toIntExact(maxChunkZ));
    }

    private static int exactOffset(long offset) {
        if (offset < -4096 || offset > 4096) {
            throw new IllegalArgumentException("受控轮廓距真实起始区块超出 4096 方块");
        }
        return Math.toIntExact(offset);
    }

    private static boolean offsetInRange(int offset) {
        return offset >= -4096 && offset <= 4096;
    }

    private static boolean supported(String version) {
        return "26.1.2".equals(version) || "26.2".equals(version);
    }

    private static boolean validId(String id) {
        return id != null && id.length() <= 256 && IDENTIFIER.matcher(id).matches();
    }
}
