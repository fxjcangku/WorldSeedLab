package io.github.fxjcangku.worldseedlab.core.solver;

import java.nio.file.Path;
import java.util.List;

/**
 * P3.2 显式离线结构候选+客户端可见群系的完整64位假设筛选。
 *
 * <p>真实远程客户端只能提交玩家正常观察到的quart群系。低48位必须通过
 * 用户单独提供的 P2.9 结构模型输入在本机 Z3 中拟合并复核；本入口不
 * 信任命令行直接输入的任意低48整数。未知 Salt、修改的生成规则、古老
 * 区块及重建建筑可能使这些模型结论不代表服务器真实完整Seed。
 *
 * <p>仅用户显式选择原版 Overworld 假设，才允许调用实际 Vanilla
 * RandomState/MultiNoiseBiomeSource。没有原版假设时不会悄悄使用原版。
 */
public final class P32OfflineClientQuartResearch {
    public static final String VANILLA_HYPOTHESIS = "EXPLICIT_VANILLA_OVERWORLD_HYPOTHESIS";

    private P32OfflineClientQuartResearch() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !VANILLA_HYPOTHESIS.equals(args[4])) {
            throw new IllegalArgumentException("用法: 客户端quart.csv 结构求解.properties "
                    + "分片索引 分片总数 EXPLICIT_VANILLA_OVERWORLD_HYPOTHESIS；"
                    + "必须显式确认采用原版Overworld模型作为待验证假设");
        }
        var archive = ClientQuartEvidenceArchive.read(Path.of(args[0]));
        var structure = SolverResearchInput.load(Path.of(args[1]));
        var scope = archive.scope();
        if (!"minecraft:overworld".equals(scope.dimension())
                || structure.constraints().isEmpty()
                || structure.constraints().stream().anyMatch(e ->
                        !scope.worldFingerprint().equals(e.worldFingerprint())
                        || !scope.gameVersion().equals(e.gameVersion())
                        || !scope.dimension().equals(e.dimension()))) {
            throw new IllegalArgumentException("客户端群系与结构证据的世界/版本/维度不同");
        }
        String configuredVersion = System.getProperty("mcVersion", "26.2");
        if (!configuredVersion.equals(scope.gameVersion())) {
            throw new IllegalArgumentException("当前Minecraft运行版本与客户端证据不一致");
        }

        var structural = LocalSmtSeedSolver.search(structure,
                SymbolicRandomSpread.Options.DEFAULT, "z3", 32);
        if (structural.candidates().isEmpty()) {
            throw new IllegalArgumentException(
                    "本机Z3未得到独立验证的low48结构候选: " + structural.status());
        }
        if (structural.incompleteRejectionPaths()) {
            System.out.println("警告：结构模型的有限nextInt拒绝采样路径不具备完整性证明");
        }
        int index = Integer.parseInt(args[2]);
        int count = Integer.parseInt(args[3]);
        Full64High16ShardPlan.Partition shard =
                Full64High16ShardPlan.partition(structural.candidates().size(), index, count);
        var model = VanillaOverworldBiomeModel.create(
                new Full64BiomeCandidateSearch.Scope(
                        scope.worldFingerprint(), scope.gameVersion(),
                        scope.dimension(), VanillaOverworldBiomeModel.GENERATOR_PROFILE));
        List<Full64BiomeCandidateSearch.BiomeSample> samples =
                archive.forVanillaHypothesis();
        var full = Full64BiomeCandidateSearch.search(
                structural.candidates(), samples, model,
                shard.budget(120_000, 128), Thread.currentThread()::isInterrupted);
        System.out.println("WorldSeedLab P3.2 客户端真实群系离线候选研究");
        System.out.println("客户端观测数量：" + samples.size()
                + "，严格水平间隔至少1024方块；结构low48候选："
                + structural.candidates().size());
        System.out.println("来源：CLIENT_OBSERVED，未经证实的生成器假设："
                + VanillaOverworldBiomeModel.GENERATOR_PROFILE);
        System.out.println("结构Z3状态：" + structural.status()
                + "；当前分片索引：" + index + "/" + count);
        System.out.println("FULL64搜索状态：" + full.completion()
                + "；实际验证次数：" + full.evaluations()
                + "；下一连续索引：" + full.nextIndex()
                + "；总候选域：" + full.domainSize());
        for (var result : full.candidates()) {
            System.out.println("FULL64_MODEL_CANDIDATE=0x" + result.hex16()
                    + " | signed=" + result.signedDecimal()
                    + " | biomeFit=" + result.fitMatches()
                    + " | biomeHoldout=" + result.holdoutMatches());
        }
        if (full.completion() != Full64BiomeCandidateSearch.Completion.REQUESTED_WINDOW_COMPLETE
                || structural.status() != LocalSmtSeedSolver.Status.BOUNDED_MODEL_EXHAUSTED) {
            System.out.println("当前结果不完整，不能声称全域已检查。");
        }
        System.out.println("重要：未得到目标服务器原版世界生成器配置的独立证明；"
                + "以上均仅是模型一致候选，不是后台真实64位Seed、矿点或结构可重建证明。");
    }
}
