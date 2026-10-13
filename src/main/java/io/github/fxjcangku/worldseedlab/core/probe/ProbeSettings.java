package io.github.fxjcangku.worldseedlab.core.probe;

/**
 * 纯客户端的有界采样设置。
 *
 * <p>精细地形取证默认关闭，必须在采集停止后由用户手动启用。
 * 已有三参数构造函数继续保留，兼容 P0/P1/P2 测试与已有配置。
 */
public record ProbeSettings(
        int radiusChunks, int verticalStride, int samplesPerTick, boolean richEvidence
) {
    public static final ProbeSettings DEFAULT = new ProbeSettings(4, 8, 128, false);

    public ProbeSettings(int radiusChunks, int verticalStride, int samplesPerTick) {
        this(radiusChunks, verticalStride, samplesPerTick, false);
    }

    public ProbeSettings {
        if (radiusChunks < 1 || radiusChunks > 6) {
            throw new IllegalArgumentException("radiusChunks must be within 1..6");
        }
        if (verticalStride != 4 && verticalStride != 8 && verticalStride != 16) {
            throw new IllegalArgumentException("verticalStride must be 4, 8 or 16");
        }
        if (samplesPerTick < 32 || samplesPerTick > 256 || samplesPerTick % 32 != 0) {
            throw new IllegalArgumentException("samplesPerTick must be 32..256 in steps of 32");
        }
    }

    public ProbeSettings withRadius(int radius) {
        return new ProbeSettings(radius, verticalStride, samplesPerTick, richEvidence);
    }

    public ProbeSettings nextVerticalStride() {
        return new ProbeSettings(radiusChunks, verticalStride == 4 ? 8 : verticalStride == 8 ? 16 : 4,
                samplesPerTick, richEvidence);
    }

    public ProbeSettings nextBudget() {
        return new ProbeSettings(radiusChunks, verticalStride,
                samplesPerTick == 256 ? 32 : samplesPerTick + 32, richEvidence);
    }

    public ProbeSettings withRichEvidence(boolean enabled) {
        return new ProbeSettings(radiusChunks, verticalStride, samplesPerTick, enabled);
    }
}
