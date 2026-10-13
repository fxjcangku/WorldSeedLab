package io.github.fxjcangku.worldseedlab.core.probe;

/** 额外地形取证精度：不覆盖独立的三维群系探针。 */
public enum DetailMode {
    OFF("关闭地表方块采集", 0),
    SURFACE("地表材质与流体", 32),
    STRATA("地表 + 地下分层", 272);

    private final String chineseLabel;
    private final int maxSamplesPerChunk;

    DetailMode(String chineseLabel, int maxSamplesPerChunk) {
        this.chineseLabel = chineseLabel;
        this.maxSamplesPerChunk = maxSamplesPerChunk;
    }

    public String chineseLabel() {
        return chineseLabel;
    }

    /** 最大额外 BlockState 读取上限，真实高度较小时可以更少。 */
    public int maximumBlockReads() {
        return maxSamplesPerChunk;
    }
}
