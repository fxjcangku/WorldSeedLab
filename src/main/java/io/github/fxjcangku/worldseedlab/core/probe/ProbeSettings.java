package io.github.fxjcangku.worldseedlab.core.probe;

/**
 * Bounded, client-local sampling configuration. Vertical stride is in blocks.
 * Horizontal spacing always follows Minecraft's four-block biome cell grid.
 */
public record ProbeSettings(int radiusChunks, int verticalStride, int samplesPerTick,
                            DetailMode detailMode) {
    /** 默认记录地表材质和流体；深入地下采样必须显式选档。 */
    public static final ProbeSettings DEFAULT = new ProbeSettings(4, 8, 128, DetailMode.SURFACE);

    /** 保留旧测试和三参数调用点的兼容性。 */
    public ProbeSettings(int radiusChunks, int verticalStride, int samplesPerTick) {
        this(radiusChunks, verticalStride, samplesPerTick, DetailMode.SURFACE);
    }

    public ProbeSettings {
        java.util.Objects.requireNonNull(detailMode, "detailMode");
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

    public ProbeSettings withRadius(int value) {
        return new ProbeSettings(value, verticalStride, samplesPerTick, detailMode);
    }

    public ProbeSettings nextVerticalStride() {
        return new ProbeSettings(radiusChunks, verticalStride == 4 ? 8 : verticalStride == 8 ? 16 : 4,
                samplesPerTick, detailMode);
    }

    public ProbeSettings nextBudget() {
        return new ProbeSettings(radiusChunks, verticalStride,
                samplesPerTick == 256 ? 32 : samplesPerTick + 32, detailMode);
    }
    /** 更换取证档位只改变下次采样计划，不自动重启已关闭的采集。 */
    public ProbeSettings nextDetailMode() {
        return new ProbeSettings(radiusChunks, verticalStride, samplesPerTick,
                switch (detailMode) {
                    case OFF -> DetailMode.SURFACE;
                    case SURFACE -> DetailMode.STRATA;
                    case STRATA -> DetailMode.OFF;
                });
    }
}
