package io.github.fxjcangku.worldseedlab.client.ui;

import io.github.fxjcangku.worldseedlab.client.probe.AutoDesertFootprintController;
import io.github.fxjcangku.worldseedlab.client.probe.WorldProbeController;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * P3.8 中文游戏聊天控制台。标题、数据、告警严格按行分开。
 *
 * <p>此处只负责向用户主动执行的客户端命令回传消息。
 * 不新增无人值守聊天刷屏，不主动上传或显示私人世界坐标。
 */
public final class WslCommandDisplay {
    private WslCommandDisplay() {}

    private static final ChatFormatting TITLE = ChatFormatting.AQUA;
    private static final ChatFormatting LABEL = ChatFormatting.GRAY;
    private static final ChatFormatting SUCCESS = ChatFormatting.GREEN;
    private static final ChatFormatting WARNING = ChatFormatting.YELLOW;
    private static final ChatFormatting ERROR = ChatFormatting.RED;
    private static final ChatFormatting VALUE = ChatFormatting.WHITE;

    private static MutableComponent styled(String value, ChatFormatting color, boolean bold) {
        return Component.literal(value).withStyle(bold
                ? new ChatFormatting[] {color, ChatFormatting.BOLD}
                : new ChatFormatting[] {color});
    }

    private static void header(Consumer<Component> send, String section) {
        send.accept(styled("━━━━━━━━  WorldSeedLab  ━━━━━━━━", TITLE, true));
        send.accept(styled("  " + section, TITLE, true));
    }

    private static void line(Consumer<Component> send, String key, String value,
                             ChatFormatting color) {
        send.accept(styled("  ▸ " + key + "  ", LABEL, false)
                .append(styled(value, color, true)));
    }

    private static void hint(Consumer<Component> send, String message) {
        send.accept(styled("  · " + message, WARNING, false));
    }

    public static void help(Consumer<Component> send) {
        header(send, "P3.8 · 中文指令导航");
        line(send, "/wsl gui  /  F8", "打开液态玻璃研究控制台", TITLE);
        line(send, "/wsl auto on", "一次授权：结构 + 群系 + 地形自动取证", SUCCESS);
        line(send, "/wsl auto status", "查看完整轮廓、材料候选与已落盘数量", VALUE);
        line(send, "/wsl auto off", "安全停止自动扫描并排空待写队列", VALUE);
        line(send, "/wsl structures", "查看自动识别的建筑类别与证据等级", TITLE);
        line(send, "/wsl detail", "切换地形详情档位（建议先设地层）", VALUE);
        line(send, "/wsl status", "查看全局证据采集进度", VALUE);
        line(send, "/wsl report  /  path", "导出报告 / 定位本地原始证据", VALUE);
        hint(send, "海底遗迹、沉船等自动分类为材料候选，不能当作自然StructureStart。");
    }

    public static void auto(Consumer<Component> send,
                            AutoDesertFootprintController auto,
                            WorldProbeController probe) {
        header(send, "自动取证 · 海洋建筑 / 自定义世界证据");
        line(send, "自动取证", auto.running() ? "已开启 · 正在扫描" : "已停止",
                auto.running() ? SUCCESS : WARNING);
        line(send, "沙漠神殿完整轮廓", String.valueOf(auto.verifiedFootprints()), TITLE);
        line(send, "严格保存成功", auto.persistedFootprints() + " 份", SUCCESS);
        line(send, "后台待写", auto.pendingFiles() + " 份", VALUE);
        line(send, "其他建筑材料候选", probe.structureCandidateGroups() + " 组", TITLE);
        line(send, "已落盘候选记录", String.valueOf(probe.structureCandidateRecordsWritten()), SUCCESS);
        line(send, "扫描批次 / 卸载中断",
                auto.completedScanPasses() + " / " + auto.interruptedScans(), VALUE);
        line(send, "轮廓不合格 / 同座去重",
                auto.rejectedFootprints() + " / " + auto.deduplicatedFootprints(), WARNING);
        line(send, "最近缓存 / 历史淘汰",
                auto.recentChunkCacheSize() + " / " + auto.evictedChunkCacheEntries(), VALUE);
        line(send, "群系 / 地形 / 地层",
                probe.observedSamples() + " / " + probe.terrainChunksWritten()
                        + " / " + probe.detailChunksWritten(), VALUE);
        line(send, "生成器 / Salt / 自然来源", "未知（UNKNOWN）", WARNING);
        hint(send, "海底神殿、冷暖海底遗迹、沉船等不需要手动逐座标记。");
        hint(send, "材料候选 ≠ 完整结构轮廓 ≠ 服务端真实结构起点。");
    }

    public static void structures(Consumer<Component> send, WorldProbeController probe) {
        header(send, "自动建筑识别 · 当前证据分类");
        line(send, "扫描", probe.structureScanEnabled() ? "已启用" : "未启用",
                probe.structureScanEnabled() ? SUCCESS : WARNING);
        line(send, "近邻材料候选组", String.valueOf(probe.structureCandidateGroups()), TITLE);
        line(send, "已经落盘的候选行数",
                String.valueOf(probe.structureCandidateRecordsWritten()), SUCCESS);
        Map<String, Long> detected = probe.detectedStructureKinds();
        if (detected.isEmpty()) {
            hint(send, "尚未命中建筑材料组合；这不表示附近或整个地图无结构。");
        } else {
            int i = 0;
            for (var entry : detected.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry.comparingByKey()))
                    .toList()) {
                if (i++ >= 12) {
                    hint(send, "其余材料候选请查看本地结构 JSONL 文件。");
                    break;
                }
                line(send, chineseStructure(entry.getKey()),
                        entry.getValue() + " 次材料命中 · 未认证天然来源", TITLE);
            }
        }
        line(send, "研究用途", "仅提供可见线索，不自动输入Seed求解器", WARNING);
    }

    public static void general(Consumer<Component> send,
                               WorldProbeController probe, AutoDesertFootprintController auto) {
        header(send, "世界生成 · 实时状态");
        line(send, "基础采集", probe.active() ? "运行中" : "未启用",
                probe.active() ? SUCCESS : WARNING);
        line(send, "多结构自动扫描", auto.running() ? "正在取证" : "已关闭",
                auto.running() ? SUCCESS : WARNING);
        line(send, "群系类型 / 观测点", probe.biomeTypes() + " / " + probe.observedSamples(), TITLE);
        line(send, "地形 / 地层完整区块",
                probe.terrainChunksWritten() + " / " + probe.detailChunksWritten(), VALUE);
        line(send, "疑似结构分组", String.valueOf(probe.structureCandidateGroups()), TITLE);
        line(send, "采样待办 / 写盘队列",
                probe.queuedChunks() + " / " + probe.pendingWrites(), VALUE);
        line(send, "生成器状态", "尚无已验证的完整64位模型", WARNING);
    }

    public static void notice(Consumer<Component> send, String module, String detail) {
        header(send, module);
        if (detail == null || detail.isBlank()) {
            line(send, "状态", "无进一步信息", WARNING);
            return;
        }
        // 历史模块仍返回紧凑状态字符串，优先逐项展开，不再把长句挤在一行。
        String[] parts = detail.split("\\s*\\|\\s*", -1);
        for (int i = 0; i < parts.length && i < 8; i++) {
            String part = parts[i].strip();
            if (part.isBlank()) continue;
            ChatFormatting tone = part.contains("失败") || part.contains("异常")
                    || part.contains("拒绝") ? ERROR
                    : part.contains("未知") || part.contains("未") || part.contains("等待")
                            ? WARNING : SUCCESS;
            line(send, i == 0 ? "当前结果" : "详细状态 " + i, part, tone);
        }
        if (parts.length > 8) {
            hint(send, "详细信息过多，请使用 /wsl auto status 分组查看。");
        }
    }

    public static String chineseStructure(String id) {
        return switch (id) {
            case "minecraft:desert_pyramid" -> "沙漠神殿";
            case "minecraft:monument" -> "海洋神殿";
            case "minecraft:shipwreck" -> "沉船";
            case "minecraft:ocean_ruin_warm" -> "暖水海底遗迹";
            case "minecraft:ocean_ruin_cold" -> "冷水海底遗迹";
            case "minecraft:ruined_portal" -> "废弃传送门";
            case "minecraft:trial_chambers" -> "试炼密室";
            case "minecraft:ancient_city" -> "远古城市";
            case "minecraft:fortress" -> "下界要塞";
            case "minecraft:bastion_remnant" -> "堡垒遗迹";
            case "minecraft:end_city" -> "末地城";
            default -> "自定义结构候选";
        };
    }
}
