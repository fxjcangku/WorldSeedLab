package io.github.fxjcangku.worldseedlab.core.generation;

import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

/**
 * 用户主动导出的可分享中文诊断摘要。
 *
 * <p>不会导出服务器地址、原始登录信息或玩家移动轨迹；
 * 原始 JSONL 仍含真实 XYZ，应在公开前单独脱敏。
 */
public final class WorldgenReport {
    private WorldgenReport() {
    }

    public static String render(
            DimensionProfile profile,
            GenerationDiagnostics.Snapshot terrain,
            Map<String, Long> observedBiomes,
            long observedQuartSamples,
            long persistedBiomeSamples,
            long persistedStructureMarks,
            long pendingWrites,
            boolean currentlyActive
    ) {
        StringBuilder out = new StringBuilder(2400);
        out.append("WorldSeedLab / P2 世界生成研究日志\n");
        out.append("导出时间（UTC）: ").append(Instant.now()).append('\n');
        out.append("研究结论: UNKNOWN（尚未建立并验证服务器结构生成模型）\n");
        out.append("完整世界 Seed: 未读取 / 未求解 / 未验证\n");
        out.append("结构种子: 未求解\n");
        out.append("世界生成历史: UNKNOWN（可能包含旧版区块）\n\n");

        out.append("【世界与维度】\n");
        out.append("本机世界指纹摘要: ")
                .append(profile.worldFingerprint(), 0, 12).append("…\n");
        out.append("客户端版本: ").append(profile.clientVersion()).append('\n');
        out.append("维度注册键: ").append(profile.dimension()).append('\n');
        out.append("建造高度范围: [").append(profile.minBuildY()).append(", ")
                .append(profile.maxBuildYExclusive()).append(")\n");
        out.append("参考海平面: ").append(profile.seaLevel()).append('\n');
        out.append("天空光照: ").append(profile.skylight() ? "有" : "无").append('\n');
        out.append("有顶维度: ").append(profile.ceiling() ? "是" : "否").append('\n');
        out.append("超高温环境: ").append(profile.ultraWarm() == null ? "未知（客户端 API 未确认）" : profile.ultraWarm() ? "是" : "否").append('\n');
        out.append("维度元数据源: 普通客户端可见信息（非服务端完整生成器配置）\n\n");

        out.append("【跑图与地形取证】\n");
        out.append("当前采集: ").append(currentlyActive ? "开启" : "关闭").append('\n');
        out.append("本次已观测群系 quart 样本: ").append(observedQuartSamples).append('\n');
        out.append("已完成持久化群系样本: ").append(persistedBiomeSamples).append('\n');
        out.append("待写入任务: ").append(pendingWrites).append('\n');
        out.append("已观测地形区块: ").append(terrain.terrainChunks()).append('\n');
        if (terrain.terrainChunks() > 0) {
            out.append("已采样 WORLD_SURFACE 高度最低/最高: ")
                    .append(terrain.lowestSurface()).append(" / ")
                    .append(terrain.highestSurface()).append('\n');
            out.append("区块内最大高度差: ").append(terrain.maximumChunkRelief()).append('\n');
            out.append("区块内平均高度差: ")
                    .append(String.format(Locale.ROOT, "%.2f", terrain.averageChunkRelief()))
                    .append('\n');
            out.append("高度差达到 32 的已采样区块: ")
                    .append(terrain.highReliefChunks()).append('\n');
        }
        out.append("注: WORLD_SURFACE 为顶层高度图稀疏采样，并非原始地形噪声。\n\n");

        out.append("【真实观察到的群系】\n");
        if (observedBiomes.isEmpty()) {
            out.append("无，尚未收到有效观测样本。\n");
        } else {
            observedBiomes.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                            .reversed().thenComparing(Map.Entry::getKey))
                    .limit(1000)
                    .forEach(e -> out.append("  ").append(e.getKey()).append(" : ")
                            .append(e.getValue()).append(" 个格点\n"));
        }
        long nonVanillaNamespaced = observedBiomes.keySet().stream()
                .filter(id -> !id.startsWith("minecraft:")).count();
        out.append("非 minecraft 命名空间的已观察群系种类: ")
                .append(nonVanillaNamespaced).append('\n');
        out.append("注: 此统计不是自定义生成器确认，也不是服务器注册表总数。\n\n");

        out.append("【建筑观察】\n");
        out.append("本次已写入的人工观察标记: ").append(persistedStructureMarks).append('\n');
        out.append("观察坐标 ≠ 结构生成起始区块；自然生成来源尚未验证。\n\n");

        out.append("【后续分析限制】\n");
        out.append("1. 没有读取或获得服务器实际世界种子。\n");
        out.append("2. 未验证原版结构随机放置算法适用于当前自定义地图。\n");
        out.append("3. 跑图不会证明未采到的区块或群系不存在。\n");
        out.append("4. 维度标识、群系出现和高度图不会单独确定 64 位 Seed。\n");
        out.append("5. 当前报告不含精确玩家坐标，原始 JSONL 含精确坐标，请勿未经脱敏公开。\n");
        out.append("6. 若仍有异步写入任务，建议稍后再次导出以得到完整计数。\n");
        return out.toString();
    }
}
