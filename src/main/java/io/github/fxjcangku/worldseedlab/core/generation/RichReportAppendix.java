package io.github.fxjcangku.worldseedlab.core.generation;

import java.util.Comparator;
import java.util.Map;

/**
 * 用户导出报告的精细观测附录，不输出 XYZ 和网络地址。
 * 只用“观察结果”措辞，避免把方块组成统计当作自然生成算法。
 */
public final class RichReportAppendix {
    private RichReportAppendix() {
    }

    public static String render(boolean enabled, long persistedChunks,
                                RichObservationStats.Snapshot stats) {
        StringBuilder out = new StringBuilder(1400);
        out.append("\n【P2 精细取证 · 已加载区块】\n");
        out.append("精细取证设置: ").append(enabled ? "开启" : "关闭").append('\n');
        out.append("已完成精细日志的区块索引: ").append(persistedChunks).append('\n');
        out.append("原始文件: rich-chunks.jsonl（含区块坐标，请勿未经脱敏公开）\n");
        if (stats == null || stats.observedChunks() == 0) {
            out.append("本次会话尚无已接受写入队列的精细样本。\n");
        } else {
            out.append("本次会话已接受的精细区块: ").append(stats.observedChunks()).append('\n');
            out.append("表层有流体的观测列: ").append(stats.fluidColumns()).append('\n');
            out.append("地下 air/cave_air 采样数量: ")
                    .append(stats.undergroundAirObservations()).append('\n');
            top(out, "地表方块", stats.surfaceBlocks());
            top(out, "地表群系", stats.surfaceBiomes());
            top(out, "稀疏地下方块", stats.deepBlocks());
        }
        out.append("每区块: 16 列 × 三种高度图、地表/海床方块、地表群系/流体；");
        out.append("另取 4 个位置 × 地表下 8/24/48/96 格。\n");
        out.append("负载上限: 1 区块/12 tick；单维度精细日志 128 MiB；默认关闭。\n");
        out.append("注意: 玩家建筑、人工改造与地图历史均可能影响观测值，");
        out.append("本阶段不据此宣称取得真实结构种子或完整 Seed。\n");
        return out.toString();
    }

    private static void top(StringBuilder out, String label, Map<String, Long> map) {
        out.append(label).append("（本次前 8 项）:\n");
        map.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .reversed().thenComparing(Map.Entry::getKey))
                .limit(8)
                .forEach(e -> out.append("  ").append(e.getKey()).append(": ")
                        .append(e.getValue()).append('\n'));
    }
}
