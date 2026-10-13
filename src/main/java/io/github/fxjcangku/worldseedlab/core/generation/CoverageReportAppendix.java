package io.github.fxjcangku.worldseedlab.core.generation;

import java.util.Comparator;
import java.util.Map;

/**
 * 中文取证质量报告。只有宏区域数量和每 32 方块高程带采样计数，
 * 不包含宏区域位置，也不对尚未访问区块推断覆盖率。
 */
public final class CoverageReportAppendix {
    private CoverageReportAppendix() {
    }

    public static String render(SamplingCoverage.Snapshot snapshot) {
        StringBuilder text = new StringBuilder(700);
        text.append("\n【采样质量与覆盖足迹】\n");
        if (snapshot == null) {
            return text.append("尚无本次会话采样覆盖资料。\n").toString();
        }
        text.append("本次已接受完整群系区块（X/Z 去重）: ")
                .append(snapshot.uniqueAcceptedChunks()).append('\n');
        text.append("涉及的 32×32 区块宏区域数: ")
                .append(snapshot.uniqueMacroRegions()).append('\n');
        text.append("已被写盘队列接受的三维群系格点: ")
                .append(snapshot.acceptedQuartSamples()).append('\n');
        text.append("区块卸载导致的未完成群系扫描: ")
                .append(snapshot.abandonedUnloadedChunks()).append('\n');
        text.append("详细方块区块在扫描中卸载: ")
                .append(snapshot.abandonedDetailChunks()).append('\n');
        text.append("磁盘队列背压发生次数: ")
                .append(snapshot.diskBackpressureCount()).append('\n');
        text.append("按高度区间统计样本（每 32 方块）:\n");
        snapshot.altitudeBandSamples().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(128)
                .forEach(entry -> text.append("  Y[")
                        .append(entry.getKey()).append(", ")
                        .append(entry.getKey() + 32).append("): ")
                        .append(entry.getValue()).append('\n'));
        text.append("重要：宏区域数量只代表曾采到的区块足迹，")
                .append("不是整个世界的地图覆盖百分比。\n");
        text.append("完成写入队列 ≠ 当前文件已经落盘，")
                .append("请等后台队列归零后导出最终报告。\n");
        return text.toString();
    }
}
