package io.github.fxjcangku.worldseedlab.core.generation;

import java.util.Comparator;
import java.util.Map;

/** 中文取证报告附录：仅汇总非定位性质的方块/群系统计，不暴露原始 XYZ。 */
public final class DetailReportAppendix {
    private DetailReportAppendix() {
    }

    public static String render(DetailObservationStats.Snapshot snapshot) {
        StringBuilder out = new StringBuilder(1200);
        out.append("\n【详细地形/地层本次取证统计】\n");
        if (snapshot == null || snapshot.acceptedChunks() == 0) {
            out.append("尚未接受任何完整的扩展区块样本。\n");
            return out.toString();
        }
        out.append("本次接受详细区块: ").append(snapshot.acceptedChunks()).append('\n');
        out.append("地表有流体的列数: ").append(snapshot.surfaceFluidColumns()).append('\n');
        out.append("分层观测总点数: ").append(snapshot.verticalCells()).append('\n');
        top(out, "地表材质（前八）", snapshot.surfaceBlocks());
        top(out, "海床材质（前八）", snapshot.oceanFloorBlocks());
        top(out, "地表实际群系（前八）", snapshot.surfaceBiomes());
        top(out, "地下分层材质（前八）", snapshot.verticalBlocks());
        out.append("只代表客户端已加载区块的观察结果，不证明原版生成算法或自然结构来源。\n");
        return out.toString();
    }

    private static void top(StringBuilder out, String title, Map<String, Long> counts) {
        out.append(title).append(":\n");
        counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .reversed().thenComparing(Map.Entry::getKey))
                .limit(8)
                .forEach(entry -> out.append("  ").append(entry.getKey()).append(": ")
                        .append(entry.getValue()).append('\n'));
    }
}
