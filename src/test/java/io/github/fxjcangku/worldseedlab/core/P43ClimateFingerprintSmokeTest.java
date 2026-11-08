package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.P43SpatialClimateEvidence;
import io.github.fxjcangku.worldseedlab.core.solver.P43ClimateFingerprintAudit;
import io.github.fxjcangku.worldseedlab.core.solver.VanillaOverworldBiomeModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * P4.3 对原版真实Minecraft FULL存档quart做独立校验，
 * 并在受控合成「气候→标签」映射上验证新跨地区分类器、错误种子候选、
 * 数据泄漏与来源拒绝。合成映射不能算作目标服生成器的身份认证。
 */
public final class P43ClimateFingerprintSmokeTest {
    private P43ClimateFingerprintSmokeTest() {}
    private static final String HMAC = "c".repeat(64);

    private static void require(boolean condition, String explanation) {
        if (!condition) throw new AssertionError(explanation);
    }

    private static void fail(Runnable body) {
        try { body.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("未拒绝错误客户端来源或独立区域角色");
    }

    private record ClimateObservation(int x, int y, int z, double temperature,
                                      P43SpatialClimateEvidence.Split split) {}

    public static void main(String[] args) throws Exception {
        String version = System.getProperty("mcVersion", "26.2");
        var scope = new Full64BiomeCandidateSearch.Scope(HMAC, version,
                "minecraft:overworld", "worldseedlab:unknown_worldgen");
        var vanillaScope = new Full64BiomeCandidateSearch.Scope(HMAC, version,
                "minecraft:overworld", VanillaOverworldBiomeModel.GENERATOR_PROFILE);
        var realSampler = VanillaOverworldBiomeModel.create(vanillaScope).prepare(2731L);
        var saved = Files.readAllLines(Path.of("src", "test", "resources", "p31",
                "vanilla_" + version + "_seed2731_quart.csv"), StandardCharsets.UTF_8);
        require(saved.size() == 6, "独立已生成FULL世界5条原始群系证据必须存在");
        var realPoints = new ArrayList<P43SpatialClimateEvidence.Point>();
        for (var item : saved.subList(1, saved.size())) {
            var parts = item.split(",", -1);
            int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            require(realSampler.biomeAtQuart(x,y,z).equals(parts[3]),
                    "真实保存的Minecraft FULL群系与本版本前向模型不一致");
            realPoints.add(new P43SpatialClimateEvidence.Point(x,y,z,parts[3],
                    realPoints.size() < 3
                            ? P43SpatialClimateEvidence.Split.FIT
                            : P43SpatialClimateEvidence.Split.HOLDOUT));
        }
        var realArchive = new P43SpatialClimateEvidence.Archive(scope,realPoints,0,0,0);
        var insufficient = P43ClimateFingerprintAudit.evaluate(realArchive,
                List.of(2731L), 8);
        require(insufficient.verdict()
                        == P43ClimateFingerprintAudit.Verdict.INSUFFICIENT_SPATIALLY_INDEPENDENT_SAMPLES,
                "5个真正FULL群系点不能伪装成具有统计能力的模型训练集");

        // 受控数据标签只由已知2731的Minecraft气候温度计算；未知服务器绝不可用作真值。
        // 真实气候向量仍由本次被检验的Minecraft代码生成；这是算法自检，而非FULL独立真值。
        var registries = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var noise = registries.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE);
        var settings = registries.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE_SETTINGS)
                .getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD).value();
        var climate = net.minecraft.world.level.levelgen.RandomState.create(
                settings,noise,2731L).sampler();
        List<ClimateObservation> sample = new ArrayList<>();
        for (int region = 0; region < 6; region++) {
            int macroX = (region-3)*8;
            int macroZ = (region-3)*11;
            for (int i = 0; i < 40; i++) {
                int qx = macroX*128+5+(i%8)*11;
                int qz = macroZ*128+7+(i/8)*16;
                int qy = 18;
                double temperature = P43ClimateFingerprintAudit.climateAxes(
                        climate.sample(qx,qy,qz))[0];
                sample.add(new ClimateObservation(qx,qy,qz,temperature,
                        region < 4 ? P43SpatialClimateEvidence.Split.FIT
                                : P43SpatialClimateEvidence.Split.HOLDOUT));
            }
        }
        double medianFit = sample.stream()
                .filter(p -> p.split() == P43SpatialClimateEvidence.Split.FIT)
                .mapToDouble(ClimateObservation::temperature).sorted()
                .skip(80).findFirst().orElseThrow();
        var points = sample.stream().map(p -> new P43SpatialClimateEvidence.Point(
                p.x(),p.y(),p.z(),
                p.temperature() >= medianFit ? "worldseedlab:controlled_warm"
                        : "worldseedlab:controlled_cool",p.split())).toList();
        var archive = new P43SpatialClimateEvidence.Archive(scope,points,0,0,0);
        require(archive.fitRegions()==4 && archive.holdoutRegions()==2
                        && archive.distinctBiomes()==2,
                "受控地区分离或气候标签的训练多样性不足");
        // 已被FIT遗漏的留出自定义群系不能由最近邻标签学习器预测。
        // 若超过25%，应拒绝用低准确率宣称NoiseRouter错误。
        var undercovered = points.stream().map(p ->
                p.split() == P43SpatialClimateEvidence.Split.HOLDOUT
                ? new P43SpatialClimateEvidence.Point(p.quartX(), p.quartY(),
                        p.quartZ(), "worldseedlab:controlled_unseen", p.split())
                : p).toList();
        var missingLabelReport = P43ClimateFingerprintAudit.evaluate(
                new P43SpatialClimateEvidence.Archive(scope, undercovered,0,0,0),
                List.of(2731L), 8);
        require(missingLabelReport.verdict()
                        == P43ClimateFingerprintAudit.Verdict.INSUFFICIENT_LABEL_COVERAGE
                        && missingLabelReport.scores().isEmpty(),
                "训练集中缺失大批留出群系时不得给出错误NoiseRouter结论");
        var evaluated = P43ClimateFingerprintAudit.evaluate(archive,
                List.of(2731L,2732L),8);
        require(evaluated.verdict()==P43ClimateFingerprintAudit.Verdict.EXPLORATORY_ONLY
                        && evaluated.fitRows()==160 && evaluated.holdoutRows()==80
                        && evaluated.scores().size()==2,
                "没有实际执行原版气候场的跨地区拟合和留出测试");
        for (var score : evaluated.scores()) {
            require(Double.isFinite(score.accuracy())
                            && score.accuracy()>=0 && score.accuracy()<=1
                            && score.baselineAccuracy()>=0 && score.baselineAccuracy()<=1
                            && score.minHoldoutRegionAccuracy()>=0
                            && score.minHoldoutRegionAccuracy()<=1
                            && score.maximumPossibleAccuracy()>=0
                            && score.maximumPossibleAccuracy()<=1,
                    "模型指标越界或不可信");
        }
        require(evaluated.scores().getFirst().accuracy() >= 0.70
                        && evaluated.scores().getFirst().accuracy()
                           > evaluated.scores().get(1).accuracy() + 0.30,
                "受控Minecraft真实Climate场上的气候→标签测试必须对正确Seed"
                + "明显优于错误Seed，不能让无鉴别力算法假通过");
        var again = P43ClimateFingerprintAudit.evaluate(archive,
                List.of(2731L,2732L),8);
        require(again.scores().equals(evaluated.scores()),
                "同一真实Minecraft气候计算必须确定性复现");
        fail(() -> new P43SpatialClimateEvidence.Archive(scope,
                new ArrayList<P43SpatialClimateEvidence.Point>() {{
                    addAll(points);
                    add(points.getFirst());
                }},0,0,0));
        var mixedRoles = new ArrayList<>(points);
        var p = points.getFirst();
        mixedRoles.set(0,new P43SpatialClimateEvidence.Point(
                p.quartX(),p.quartY(),p.quartZ(),p.biomeId(),
                P43SpatialClimateEvidence.Split.HOLDOUT));
        fail(() -> new P43SpatialClimateEvidence.Archive(scope,mixedRoles,0,0,0));
        fail(() -> P43ClimateFingerprintAudit.evaluate(archive,
                List.of(2731L,2731L),8));
        fail(() -> P43ClimateFingerprintAudit.evaluate(archive,
                List.of(2731L,2732L),1));

        var file = Files.createTempFile("wsl-p43-controlled-", ".csv");
        try {
            var encoded = new StringBuilder(P43SpatialClimateEvidence.MAGIC)
                    .append("\nworldFingerprint=").append(HMAC)
                    .append("\ngameVersion=").append(version)
                    .append("\ndimension=minecraft:overworld")
                    .append("\ngeneratorProfile=worldseedlab:unknown_worldgen")
                    .append("\nprovenance=CLIENT_OBSERVED")
                    .append("\nsampling=AUTOMATIC_FULL_CHUNK_OBSERVATION")
                    .append("\nquart_x,quart_y,quart_z,biome_id,split\n");
            for (var point : points) {
                encoded.append(point.quartX()).append(',').append(point.quartY())
                        .append(',').append(point.quartZ()).append(',')
                        .append(point.biomeId()).append(',').append(point.split())
                        .append('\n');
            }
            Files.writeString(file,encoded,StandardCharsets.UTF_8);
            var parsed=P43SpatialClimateEvidence.read(file);
            require(parsed.points().size()==points.size()
                            && parsed.fitRegions()==4 && parsed.holdoutRegions()==2,
                    "客户端临时证据导出/重新导入不一致");
            Files.writeString(file,encoded.toString().replace(
                    "provenance=CLIENT_OBSERVED","provenance=CONTROL_WORLD_OBSERVED"));
            try { P43SpatialClimateEvidence.read(file); throw new AssertionError(
                    "非真实客户端来源被当作原始远端群系"); }
            catch (java.io.IOException expected) { /* fail closed */ }
        } finally {
            Files.deleteIfExists(file);
        }
        System.out.printf(java.util.Locale.ROOT,
                "P4.3 actual Minecraft %s saved FULL parity PASS;"
                + " synthetic mapping positiveSeed accuracy=%.3f wrongSeed=%.3f baseline=%.3f;"
                + " 4FIT/2HOLDOUT, strict provenance and deterministic results PASS;"
                + " target server Seed still UNKNOWN%n",
                version, evaluated.scores().getFirst().accuracy(),
                evaluated.scores().get(1).accuracy(),
                evaluated.scores().getFirst().baselineAccuracy());
    }
}
