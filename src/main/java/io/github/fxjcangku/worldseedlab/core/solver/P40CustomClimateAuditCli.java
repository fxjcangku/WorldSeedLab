package io.github.fxjcangku.worldseedlab.core.solver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P4.0 显式离线候选对照。输入是未经确认的自定义 MultiNoise 气候假设；
 * 不能绕过 P3.9 的天然结构门槛，也不能据此发布真实64位 Seed。
 */
public final class P40CustomClimateAuditCli {
    private P40CustomClimateAuditCli() {}

    public enum Verdict {
        REJECTED_BY_REGISTRY, REJECTED_BY_FIT, REJECTED_BY_HOLDOUT,
        CONDITIONAL_MATCH_UNVERIFIED
    }

    public record CandidateResult(long fullSeedBits, Verdict verdict) {}

    public static List<CandidateResult> compare(
            ClientQuartEvidenceArchive.Archive archive,
            P40CustomClimateModel.Specification spec, List<Long> fullSeedBits) {
        if (fullSeedBits.isEmpty() || fullSeedBits.size() > 32
                || new HashSet<>(fullSeedBits).size() != fullSeedBits.size()) {
            throw new IllegalArgumentException("显式完整64位候选必须是1..32个不同整数");
        }
        if (!"minecraft:overworld".equals(archive.scope().dimension())) {
            throw new IllegalArgumentException("目前仅支持主世界气候模型，维度不能混合");
        }
        List<CandidateResult> results = new ArrayList<>();
        if (archive.samples().stream().anyMatch(s -> !spec.possibleBiomes().contains(s.observedBiome()))) {
            for (long bits : fullSeedBits) {
                results.add(new CandidateResult(bits, Verdict.REJECTED_BY_REGISTRY));
            }
            return results;
        }
        var assumedScope = new Full64BiomeCandidateSearch.Scope(
                archive.scope().worldFingerprint(), archive.scope().gameVersion(),
                archive.scope().dimension(), spec.generatorProfile());
        var model = P40CustomClimateModel.create(assumedScope, spec);
        for (long seed : fullSeedBits) {
            var prediction = model.prepare(seed);
            Verdict verdict = Verdict.CONDITIONAL_MATCH_UNVERIFIED;
            for (var point : archive.samples()) {
                if (point.split() != Full64BiomeCandidateSearch.Split.FIT) continue;
                if (!point.observedBiome().equals(prediction.biomeAtQuart(
                        point.quartX(), point.quartY(), point.quartZ()))) {
                    verdict = Verdict.REJECTED_BY_FIT;
                    break;
                }
            }
            if (verdict == Verdict.CONDITIONAL_MATCH_UNVERIFIED) {
                for (var point : archive.samples()) {
                    if (point.split() != Full64BiomeCandidateSearch.Split.HOLDOUT) continue;
                    if (!point.observedBiome().equals(prediction.biomeAtQuart(
                            point.quartX(), point.quartY(), point.quartZ()))) {
                        verdict = Verdict.REJECTED_BY_HOLDOUT;
                        break;
                    }
                }
            }
            results.add(new CandidateResult(seed, verdict));
        }
        return List.copyOf(results);
    }

    private static List<Long> parseSeeds(String comma) {
        if (comma == null || comma.length() > 650) throw new IllegalArgumentException("候选清单超限");
        List<Long> seeds = new ArrayList<>();
        for (String part : comma.split(",", -1)) {
            if (part.isBlank()) throw new IllegalArgumentException("空 Seed 不可参与筛选");
            String value = part.trim();
            seeds.add(value.startsWith("0x")
                    ? Long.parseUnsignedLong(value.substring(2), 16)
                    : Long.parseLong(value));
        }
        return seeds;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "用法: CLIENT_OBSERVED_quart.csv 本地气候模型.json 游戏版本 候选Seed列表");
        }
        var archive = ClientQuartEvidenceArchive.read(Path.of(args[0]));
        var spec = P40CustomClimateModel.read(Path.of(args[1]));
        if (!archive.scope().gameVersion().equals(args[2])
                || !args[2].equals(System.getProperty("mcVersion", "26.2"))) {
            throw new IllegalArgumentException("客户端证据与当前Minecraft目标版本不一致");
        }
        var results = compare(archive, spec, parseSeeds(args[3]));
        System.out.println("WorldSeedLab P4.0 真实 Minecraft 气候前向模型假设检验");
        System.out.println("EVIDENCE=CLIENT_OBSERVED samples=" + archive.samples().size()
                + " FIT=3 HOLDOUT=" + (archive.samples().size() - 3));
        System.out.println("MODEL=" + spec.generatorProfile()
                + " SOURCE=USER_SUPPLIED_UNVERIFIED NOISE_ROUTER=VANILLA_OVERWORLD_ASSUMPTION");
        for (var result : results) {
            System.out.println("MODEL_TEST_SEED=0x%016x VERDICT=%s".formatted(
                    result.fullSeedBits(), result.verdict()));
        }
        System.out.println("LOW48=NOT_DERIVED FULL64=UNKNOWN_CUSTOM_WORLDGEN");
        System.out.println("仅评估显式候选；没有搜索全部64位、没有认证后台生成器或天然结构。"
                + "不显示原始坐标、世界HMAC或服务端地址。");
    }
}
