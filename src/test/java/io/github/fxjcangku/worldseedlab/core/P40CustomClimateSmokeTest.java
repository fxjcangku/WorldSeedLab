package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.ClientQuartEvidenceArchive;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.P40CustomClimateAuditCli;
import io.github.fxjcangku.worldseedlab.core.solver.P40CustomClimateModel;
import io.github.fxjcangku.worldseedlab.core.solver.VanillaOverworldBiomeModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 独立已生成 FULL 存档群系的受控真值，验证可插拔 climate 参数表
 * 与目标 Minecraft 自身的 VanillaOverworldBiomeModel 在两个版本完全一致。
 * 不把受控 vanilla 命中冒充未知 Terralith 服务端恢复。
 */
public final class P40CustomClimateSmokeTest {
    private P40CustomClimateSmokeTest() {}
    private static final String HASH = "e".repeat(64);

    public static void main(String[] args) throws Exception {
        String version = System.getProperty("mcVersion", "26.2");
        var archiveSession = new ClientQuartEvidenceArchive.Session(
                HASH, version, "minecraft:overworld");
        var saved = Files.readAllLines(Path.of("src", "test", "resources", "p31",
                "vanilla_" + version + "_seed2731_quart.csv"));
        if (saved.size() != 6 || !saved.getFirst().equals("quart_x,quart_y,quart_z,biome_id")) {
            throw new AssertionError("真实 FULL 区块生成样本缺失");
        }
        var scope = new Full64BiomeCandidateSearch.Scope(HASH, version,
                "minecraft:overworld", VanillaOverworldBiomeModel.GENERATOR_PROFILE);
        var vanilla = VanillaOverworldBiomeModel.create(scope).prepare(2731L);
        for (String line : saved.subList(1, saved.size())) {
            String[] cells = line.split(",", -1);
            int qx = Integer.parseInt(cells[0]);
            int qy = Integer.parseInt(cells[1]);
            int qz = Integer.parseInt(cells[2]);
            if (!cells[3].equals(vanilla.biomeAtQuart(qx, qy, qz))) {
                throw new AssertionError("独立已生成FULL原版群系真值不匹配");
            }
            archiveSession.addQuart(qx, qy, qz, cells[3]);
        }
        var archive = archiveSession.snapshot();
        var spec = P40CustomClimateModel.controlledVanillaPreset("worldseedlab:control_vanilla");
        var tests = P40CustomClimateAuditCli.compare(archive, spec,
                List.of(2731L, 2732L));
        if (tests.size() != 2 || tests.getFirst().verdict()
                != P40CustomClimateAuditCli.Verdict.CONDITIONAL_MATCH_UNVERIFIED
                || tests.get(1).verdict()
                == P40CustomClimateAuditCli.Verdict.CONDITIONAL_MATCH_UNVERIFIED) {
            throw new AssertionError("受控真实FULL群系必须保留2731并排除错误候选2732");
        }
        if (P40CustomClimateModel.create(new Full64BiomeCandidateSearch.Scope(
                HASH, version, "minecraft:overworld", spec.generatorProfile()), spec).trust()
                != Full64BiomeCandidateSearch.ModelTrust.UNVERIFIED_OR_CUSTOM) {
            throw new AssertionError("本模型不能擅自升级来源可信级别");
        }
        var wrong = new P40CustomClimateModel.Specification(
                "worldseedlab:control_wrong", P40CustomClimateModel.SAMPLER,
                spec.entries().stream().map(p ->
                        com.mojang.datafixers.util.Pair.of(p.getFirst(), "worldseedlab:wrong"))
                        .toList(), java.util.Set.of("worldseedlab:wrong"));
        var rejected = P40CustomClimateAuditCli.compare(archive, wrong, List.of(2731L));
        if (rejected.getFirst().verdict() != P40CustomClimateAuditCli.Verdict.REJECTED_BY_REGISTRY) {
            throw new AssertionError("假注册表与实际FULL群系冲突时必须在运行候选前拒绝");
        }
        expectFailure(() -> P40CustomClimateAuditCli.compare(archive, spec,
                List.of(2731L,2731L)));
        expectFailure(() -> P40CustomClimateModel.create(
                new Full64BiomeCandidateSearch.Scope(HASH, version,
                        "minecraft:the_nether",spec.generatorProfile()),spec));
        System.out.println("P4.0 target Minecraft " + version
                + " saved FULL biomes -> explicit MultiNoise parameter forward parity PASS"
                + "; correct=2731 wrong=2732; no custom-server Seed claim.");
    }

    private static void expectFailure(Runnable task) {
        try { task.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("未拒绝非法候选或错误维度");
    }
}
