package io.github.fxjcangku.worldseedlab.core.probe;

import java.util.Objects;

/**
 * 世界采样配置：原有三维群系扫描与可选多高度图/纵向剖面各自有独立档位。
 * 深度取证均默认关闭；启动采集本身也永不写入配置文件。
 */
public record ProbeSettings(int radiusChunks, int verticalStride, int samplesPerTick,
                            DetailMode detailMode, boolean richEvidence) {
    public static final ProbeSettings DEFAULT =
            new ProbeSettings(4, 8, 128, DetailMode.OFF, false);

    public ProbeSettings(int radiusChunks, int verticalStride, int samplesPerTick) {
        this(radiusChunks, verticalStride, samplesPerTick, DetailMode.OFF, false);
    }

    public ProbeSettings(int radiusChunks, int verticalStride, int samplesPerTick, boolean richEvidence) {
        this(radiusChunks, verticalStride, samplesPerTick, DetailMode.OFF, richEvidence);
    }

    public ProbeSettings(int radiusChunks, int verticalStride, int samplesPerTick, DetailMode detailMode) {
        this(radiusChunks, verticalStride, samplesPerTick, detailMode, false);
    }

    public ProbeSettings {
        Objects.requireNonNull(detailMode, "detailMode");
        if (radiusChunks < 1 || radiusChunks > 6) {
            throw new IllegalArgumentException("radiusChunks must be 1..6");
        }
        if (verticalStride != 4 && verticalStride != 8 && verticalStride != 16) {
            throw new IllegalArgumentException("verticalStride must be 4/8/16");
        }
        if (samplesPerTick < 32 || samplesPerTick > 256 || samplesPerTick % 32 != 0) {
            throw new IllegalArgumentException("samplesPerTick must be 32..256 step 32");
        }
    }

    public ProbeSettings withRadius(int value) {
        return new ProbeSettings(value, verticalStride, samplesPerTick, detailMode, richEvidence);
    }

    public ProbeSettings nextVerticalStride() {
        return new ProbeSettings(radiusChunks, verticalStride == 4 ? 8 : verticalStride == 8 ? 16 : 4,
                samplesPerTick, detailMode, richEvidence);
    }

    public ProbeSettings nextBudget() {
        return new ProbeSettings(radiusChunks, verticalStride,
                samplesPerTick == 256 ? 32 : samplesPerTick + 32, detailMode, richEvidence);
    }

    public ProbeSettings withRichEvidence(boolean enabled) {
        return new ProbeSettings(radiusChunks, verticalStride, samplesPerTick, detailMode, enabled);
    }

    public ProbeSettings nextDetailMode() {
        return new ProbeSettings(radiusChunks, verticalStride, samplesPerTick,
                switch (detailMode) {
                    case OFF -> DetailMode.SURFACE;
                    case SURFACE -> DetailMode.STRATA;
                    case STRATA -> DetailMode.OFF;
                }, richEvidence);
    }
}
