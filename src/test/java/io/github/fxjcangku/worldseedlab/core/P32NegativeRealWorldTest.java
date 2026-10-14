package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.LocalSmtSeedSolver;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadMath;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import io.github.fxjcangku.worldseedlab.core.solver.VanillaOverworldBiomeModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P3.2 负数完整64位 Seed 的真正 Minecraft 世界生成正反例。
 *
 * <p>真实客户端不接收服务端 StructureStart。本程序只核对自动化流程生成的
 * 独立受控服务器 NBT：高16位 0xBEEF 和真实已保存 FULL 区块的 quart 群系；
 * 与本体 VanillaOverworldBiomeModel 前向算法对照，并拒绝高16=0的同低48种子。
 */
public final class P32NegativeRealWorldTest {
    private static final long FULL64_BITS = 0xBEEF000000000AABL;
    private static final int HIGH16 = 0xBEEF;
    private static final long LOW48 = 2731L;
    private static final String WORLD = "f".repeat(64);

    private P32NegativeRealWorldTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("参数: 真实StructureStart CSV 实际FULL群系CSV 游戏版本");
        }
        String version = args[2];
        if (!"26.1.2".equals(version) && !"26.2".equals(version)) {
            throw new IllegalArgumentException("版本必须来自目标Minecraft");
        }
        Path startPath = Path.of(args[0]);
        List<String> startLines = Files.readAllLines(startPath);
        check(startLines.size() >= 4 && startLines.getFirst().equals("chunk_x,chunk_z"),
                "至少3条真正生成的神殿StructureStart");
        var spec = new RandomSpreadSpec("minecraft:desert_pyramids", 32, 8,
                RandomSpreadSpec.Distribution.LINEAR, 14357617);
        Set<String> startRegions = new HashSet<>();
        int verified = 0;
        for (String row : startLines.subList(1, startLines.size())) {
            if (row.isBlank()) continue;
            String[] fields = row.split(",", -1);
            check(fields.length == 2, "控制世界结构坐标非法");
            int x = Integer.parseInt(fields[0]), z = Integer.parseInt(fields[1]);
            String region = Math.floorDiv(x, spec.spacing()) + ":"
                    + Math.floorDiv(z, spec.spacing());
            check(startRegions.add(region), "重复结构Region不得提高验证样本量");
            var prediction = RandomSpreadMath.potentialFromSourceChunk(FULL64_BITS, x, z, spec);
            check(prediction.x() == x && prediction.z() == z,
                    "带负数完整Seed的真实神殿不符合其low48结构生成规则");
            verified++;
        }
        check(verified >= 3, "真实结构样本不足，不能证明low48");

        List<String> lines = Files.readAllLines(Path.of(args[1]));
        check(lines.size() >= 6 && lines.getFirst().equals(
                "quart_x,quart_y,quart_z,biome_id"),
                "独立保存的FULL区块群系必须至少5条");
        var scope = new Full64BiomeCandidateSearch.Scope(
                WORLD, version, "minecraft:overworld",
                VanillaOverworldBiomeModel.GENERATOR_PROFILE);
        var model = VanillaOverworldBiomeModel.create(scope);
        var actual = model.prepare(FULL64_BITS);
        var incorrectSameLow = model.prepare(LOW48);
        List<Full64BiomeCandidateSearch.BiomeSample> samples = new ArrayList<>();
        Set<String> distinct = new HashSet<>();
        int differences = 0;
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] parts = line.split(",", -1);
            check(parts.length == 4, "群系取证CSV结构损坏");
            int qx = Integer.parseInt(parts[0]);
            int qy = Integer.parseInt(parts[1]);
            int qz = Integer.parseInt(parts[2]);
            String biome = parts[3];
            check(distinct.add(qx+":"+qy+":"+qz), "不能重复计算已观察quart");
            String predicted = actual.biomeAtQuart(qx,qy,qz);
            check(biome.equals(predicted),
                    "负数Seed真实Minecraft存档群系不能被本体模型复算："
                    + qx + "," + qy + "," + qz
                    + " true=" + biome + " modeled=" + predicted);
            if (!biome.equals(incorrectSameLow.biomeAtQuart(qx,qy,qz))) differences++;
            if (samples.size() < 12) {
                samples.add(new Full64BiomeCandidateSearch.BiomeSample(
                        scope, qx,qy,qz,biome,
                        samples.size() < 3 ? Full64BiomeCandidateSearch.Split.FIT
                                : Full64BiomeCandidateSearch.Split.HOLDOUT,
                        Full64BiomeCandidateSearch.Origin.CONTROL_WORLD_OBSERVED));
            }
        }
        check(samples.size() >= 5 && differences >= 1,
                "真实群系证据必须对高16位BEEF敏感");

        List<LocalSmtSeedSolver.VerifiedLow48> structuralCandidates =
                List.of(new LocalSmtSeedSolver.VerifiedLow48(LOW48,2,1));
        var aroundTrue = Full64BiomeCandidateSearch.search(
                structuralCandidates, samples, model,
                new Full64BiomeCandidateSearch.SearchBudget(
                        HIGH16-1, HIGH16+2, 3, 120_000, 8), () -> false);
        check(aroundTrue.completion()
                        == Full64BiomeCandidateSearch.Completion.REQUESTED_WINDOW_COMPLETE
                && aroundTrue.evaluations() == 3
                && aroundTrue.candidates().size() == 1
                && aroundTrue.candidates().getFirst().fullSeedBits() == FULL64_BITS
                && aroundTrue.candidates().getFirst().holdoutMatches() >= 2,
                "必须从实际保存群系中恢复负数完整64位Seed的高16位BEEF");
        var wrongZero = Full64BiomeCandidateSearch.search(
                structuralCandidates, samples, model,
                new Full64BiomeCandidateSearch.SearchBudget(0,1,1,120_000,8), () -> false);
        check(wrongZero.candidates().isEmpty(),
                "高16=0虽和真实结构low48一致，必须被独立群系排除");

        System.out.println("WorldSeedLab P3.2 REAL negative full64 Minecraft " + version
                + " verified: 0x" + Long.toHexString(FULL64_BITS)
                + " signed=" + FULL64_BITS
                + " realStarts=" + verified + " fullChunkQuart=" + distinct.size()
                + " biomeDifferentFromHigh16Zero=" + differences
                + " FIT=3 HOLDOUT=" + (samples.size()-3)
                + " high16=0xBEEF retained, high16=0 and neighbors rejected.");
        System.out.println("严格边界：受控原版世界的窄搜索窗口真值，不是普通客户端"
                + "runClient实测，不是未知自定义服务器完整64位Seed。");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
