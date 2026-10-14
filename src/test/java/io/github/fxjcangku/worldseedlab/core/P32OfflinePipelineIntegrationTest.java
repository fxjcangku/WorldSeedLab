package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.ClientQuartEvidenceArchive;
import io.github.fxjcangku.worldseedlab.core.solver.CandidateSeedSearch;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.LocalSmtSeedSolver;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import io.github.fxjcangku.worldseedlab.core.solver.SolverResearchInput;
import io.github.fxjcangku.worldseedlab.core.solver.StartPlacementConstraint;
import io.github.fxjcangku.worldseedlab.core.solver.SymbolicRandomSpread;
import io.github.fxjcangku.worldseedlab.core.solver.VanillaOverworldBiomeModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * P3.2 结构真实起点真值 -> 原创Z3 low48 -> 模拟客户端手动quart格式
 * -> 原版群系正向反例 -> 高16位候选 的受控集成测试。
 *
 * <p>群系数值来自真实 Minecraft 已保存 FULL 区块，但在此由测试代码
 * **模拟客户端传输格式**。本测试不等同于GUI runClient进入单人世界采集。
 */
public final class P32OfflinePipelineIntegrationTest {
    private static final String WORLD = "e".repeat(64);
    private static final int[][] REAL_STARTS = {
            {-784,749}, {-1166,-1044}, {-117,-106}, {930,791}
    };

    private P32OfflinePipelineIntegrationTest() {}

    public static void main(String[] args) throws Exception {
        String version = System.getProperty("mcVersion", "26.2");
        var spec = new RandomSpreadSpec("minecraft:desert_pyramids", 32, 8,
                RandomSpreadSpec.Distribution.LINEAR, 14357617);
        var constraints = new java.util.ArrayList<StartPlacementConstraint>();
        for (int i=0; i<REAL_STARTS.length; i++) {
            int[] pos = REAL_STARTS[i];
            constraints.add(StartPlacementConstraint.controlledExact(
                    WORLD, version, "minecraft:overworld",
                    spec.structureSetId(), pos[0], pos[1],
                    i < 3 ? StartPlacementConstraint.Split.FIT
                            : StartPlacementConstraint.Split.HOLDOUT));
        }
        var input = new SolverResearchInput(Map.of(spec.structureSetId(),spec),
                constraints,
                new CandidateSeedSearch.SearchWindow(0,4096,4096,32,60_000));
        var low = LocalSmtSeedSolver.search(input,
                new SymbolicRandomSpread.Options(3,60_000,96), "z3", 16);
        check(low.candidates().stream().anyMatch(candidate ->
                candidate.seedLow48()==2731L && candidate.fitMatches()==3
                        && candidate.holdoutMatches()==1),
                "真正生成神殿起点必须由本机Z3回推low48=2731");

        Path saved = Path.of("src","test","resources","p31",
                "vanilla_" + version + "_seed2731_quart.csv");
        List<String> rows = Files.readAllLines(saved);
        var transport = new ClientQuartEvidenceArchive.Session(
                WORLD, version, "minecraft:overworld");
        for (String line : rows.subList(1,rows.size())) {
            String[] columns = line.split(",", -1);
            transport.addQuart(Integer.parseInt(columns[0]), Integer.parseInt(columns[1]),
                    Integer.parseInt(columns[2]), columns[3]);
        }
        var archive = ClientQuartEvidenceArchive.decode(
                ClientQuartEvidenceArchive.encode(transport.snapshot()));
        check(archive.samples().size()==5
                        && archive.scope().generatorProfile().equals(
                            ClientQuartEvidenceArchive.UNVERIFIED_PROFILE),
                "模拟客户端证据在独立存档值下保持来源及生成器未知身份");
        var model = VanillaOverworldBiomeModel.create(
                new Full64BiomeCandidateSearch.Scope(
                        WORLD, version,"minecraft:overworld",
                        VanillaOverworldBiomeModel.GENERATOR_PROFILE));
        var near = Full64BiomeCandidateSearch.search(
                low.candidates(), archive.forVanillaHypothesis(), model,
                new Full64BiomeCandidateSearch.SearchBudget(0,2,2,120_000,8),
                () -> false);
        check(near.completion()
                        == Full64BiomeCandidateSearch.Completion.REQUESTED_WINDOW_COMPLETE
                && near.candidates().size()==1
                && near.candidates().getFirst().fullSeedBits()==2731L
                && near.candidates().getFirst().holdoutMatches()==2,
                "真实存档群系经模拟客户端传输后应保留2731并排除错误high16");
        System.out.println("WorldSeedLab P3.2 actual vanilla structure Z3 + "
                + "simulated client quart transport integration passed: "
                + version + " LOW48=2731, 3 structure FIT/1 HOLDOUT, "
                + "3 biome FIT/2 HOLDOUT, FULL64=2731 survives and high16=1 rejected.");
        System.out.println("口径：客户端数据格式为测试模拟；并非真实 runClient 交互记录。");
    }

    private static void check(boolean pass, String description) {
        if(!pass) throw new AssertionError(description);
    }
}
