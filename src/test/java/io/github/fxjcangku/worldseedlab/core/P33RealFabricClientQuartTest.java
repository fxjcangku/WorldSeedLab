package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.ClientQuartEvidenceArchive;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.LocalSmtSeedSolver;
import io.github.fxjcangku.worldseedlab.core.solver.VanillaOverworldBiomeModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * P3.3 真正由 Fabric 客户端连接隔离原版服务器后，用户聊天指令导出的 CSV
 * 在独立测试 JVM 中验证。数据来源必须为 CLIENT_OBSERVED 而非模拟传输。
 *
 * <p>服务器Seed=2731是公开受控实验真值，低48位来自先前独立StructureStart
 * 的P2.9验证；本测试不称其为普通远端未知服务器独立low48求解。
 */
public final class P33RealFabricClientQuartTest {
    private P33RealFabricClientQuartTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("用法: 实际客户端生成的CSV 游戏版本");
        }
        var version = args[1];
        if (!"26.1.2".equals(version) && !"26.2".equals(version)) {
            throw new IllegalArgumentException("目标版本非法");
        }
        Path path = Path.of(args[0]);
        var clientEvidence = ClientQuartEvidenceArchive.read(path);
        if (!version.equals(clientEvidence.scope().gameVersion())
                || !"minecraft:overworld".equals(clientEvidence.scope().dimension())
                || !ClientQuartEvidenceArchive.UNVERIFIED_PROFILE.equals(
                        clientEvidence.scope().generatorProfile())
                || clientEvidence.samples().size() != 5) {
            throw new AssertionError("客户端实际CSV版本/维度/群系来源或样本数错误");
        }

        var original = Files.readAllLines(Path.of("src","test","resources","p31",
                "vanilla_" + version + "_seed2731_quart.csv"));
        if (original.size() != 6) {
            throw new AssertionError("公开真实世界 Anvil FULL 群系真值文件不存在");
        }
        for (int i = 0; i < 5; i++) {
            var row = original.get(i+1).split(",");
            var actual = clientEvidence.samples().get(i);
            if (row.length != 4
                    || actual.quartX() != Integer.parseInt(row[0])
                    || actual.quartY() != Integer.parseInt(row[1])
                    || actual.quartZ() != Integer.parseInt(row[2])
                    || !actual.observedBiome().equals(row[3])) {
                throw new AssertionError("真实CLIENT_OBSERVED不是对应坐标的真实FULL存档群系: "
                        + i + " client=" + actual + " expected="+original.get(i+1));
            }
            if (actual.origin() != Full64BiomeCandidateSearch.Origin.CLIENT_OBSERVED
                    || actual.split() != (i < 3
                    ? Full64BiomeCandidateSearch.Split.FIT
                    : Full64BiomeCandidateSearch.Split.HOLDOUT)) {
                throw new AssertionError("客户端证据角色/来源类型错误");
            }
        }

        var input = clientEvidence.forVanillaHypothesis();
        var model = VanillaOverworldBiomeModel.create(input.getFirst().scope());
        for (var sample : input) {
            var predicted = model.prepare(2731L).biomeAtQuart(
                    sample.quartX(), sample.quartY(), sample.quartZ());
            if (!predicted.equals(sample.observedBiome())) {
                throw new AssertionError("真实客户端群系与正确原版完整Seed的算法不一致");
            }
        }
        var result = Full64BiomeCandidateSearch.search(
                List.of(new LocalSmtSeedSolver.VerifiedLow48(2731L,2,1)),
                input, model,
                new Full64BiomeCandidateSearch.SearchBudget(0,8,8,120_000,8),
                () -> false);
        if (result.completion() != Full64BiomeCandidateSearch.Completion.REQUESTED_WINDOW_COMPLETE
                || result.evaluations() != 8
                || result.candidates().size() != 1
                || result.candidates().getFirst().fullSeedBits() != 2731L
                || result.candidates().getFirst().holdoutMatches() != 2) {
            throw new AssertionError("真正客户端CSV未能在明确模型下保留2731并排除其他高位");
        }
        System.out.println("WorldSeedLab P3.3 REAL Fabric protocol GUI/client chat evidence "
                + version + ": 5 MANUAL CLIENT_OBSERVED quart locations exactly match "
                + "true Minecraft FULL-chunk Anvil biomes, 3 FIT + 2 HOLDOUT; "
                + "controlled full64=2731 passes vanilla model and high16 [1..7] excluded.");
        System.out.println("限定：使用公开已知Seed的回环原版服务器，低48来源为独立受控实验；"
                + "该证明不等于未知服务器自定义地形Seed恢复，也不代表完整GUI界面截图验收。");
    }
}
