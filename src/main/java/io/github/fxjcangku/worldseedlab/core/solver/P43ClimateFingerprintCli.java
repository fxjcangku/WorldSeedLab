package io.github.fxjcangku.worldseedlab.core.solver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端真实P1稀疏气候位点→用户明确指定完整64位假设→原版Overworld
 * NoiseRouter气候向量计算→跨地区留出对照。不得打印原始XYZ/HMAC。
 */
public final class P43ClimateFingerprintCli {
    private P43ClimateFingerprintCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !"EXPLICIT_UNVERIFIED_VANILLA_NOISE_ROUTER".equals(args[2])) {
            throw new IllegalArgumentException(
                    "P4.3需依次传入本机CLIENT_OBSERVED空间quart证据、"
                    + "最多8个用户显式完整64位Seed、EXPLICIT_UNVERIFIED_VANILLA_NOISE_ROUTER");
        }
        var archive = P43SpatialClimateEvidence.read(Path.of(args[0]));
        List<Long> candidates = new ArrayList<>();
        if (args[1].length() > 190) {
            throw new IllegalArgumentException("显式候选清单过长");
        }
        for (String token : args[1].split(",", -1)) {
            String value = token.strip();
            if (value.isEmpty() || candidates.size() >= 8) {
                throw new IllegalArgumentException("显式完整64位候选必须为1..8个不同值");
            }
            candidates.add(value.startsWith("0x")
                    ? Long.parseUnsignedLong(value.substring(2), 16) : Long.parseLong(value));
        }
        var result = P43ClimateFingerprintAudit.evaluate(archive, candidates, 8);
        System.out.println("WorldSeedLab P4.3 · CLIENT_OBSERVED 气候场指纹泛化性研究");
        System.out.println("MC=" + archive.scope().gameVersion()
                + " | 输入=已加载FULL区块实际quart | 生成器=UNKNOWN");
        System.out.println("FIT_REGION=" + result.fitRegions()
                + " FIT_ROWS=" + result.fitRows()
                + " HOLDOUT_REGION=" + result.holdoutRegions()
                + " HOLDOUT_ROWS=" + result.holdoutRows()
                + " FIT_BIOME_KINDS=" + result.fitLabelKinds());
        System.out.println("NOISE_ROUTER_HYPOTHESIS=minecraft:vanilla_overworld"
                + " | PROVENANCE=EXPLICIT_UNVERIFIED | VERDICT=" + result.verdict());
        for (var score : result.scores()) {
            System.out.printf(java.util.Locale.ROOT,
                    "HYPOTHESIS_SEED=0x%s | HOLDOUT_ACCURACY=%.6f"
                    + " | MAJORITY_BASELINE=%.6f | WORST_HOLDOUT_REGION=%.6f"
                    + " | UNSEEN_LABEL_ROWS=%d%n",
                    score.hexSeed(), score.accuracy(), score.baselineAccuracy(),
                    score.minHoldoutRegionAccuracy(), score.unseenHoldoutLabels());
        }
        System.out.println("RESULT=EXPLORATORY_COMPATIBILITY_ONLY"
                + " GENERATOR_IDENTITY=UNKNOWN LOW48=NOT_DERIVED"
                + " FULL64=UNKNOWN_CUSTOM_WORLDGEN");
        System.out.println("注意：以上仅评估给定Seed假设是否比多数类基线更能"
                + "预测独立地区标签；不是全位域破解、不是Terralith/服务端配置认证。");
    }
}
