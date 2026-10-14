package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.compatibility.P34WorldgenCompatibilityAudit;
import io.github.fxjcangku.worldseedlab.core.compatibility.P34WorldgenCompatibilityAudit.ChunkHistory;
import io.github.fxjcangku.worldseedlab.core.compatibility.P34WorldgenCompatibilityAudit.SaltKnowledge;
import io.github.fxjcangku.worldseedlab.core.compatibility.P34WorldgenCompatibilityAudit.Scope;
import io.github.fxjcangku.worldseedlab.core.compatibility.P34WorldgenCompatibilityAudit.StructureEvidence;
import io.github.fxjcangku.worldseedlab.core.compatibility.P34WorldgenCompatibilityAudit.StructureProvenance;
import io.github.fxjcangku.worldseedlab.core.compatibility.P34WorldgenCompatibilityAudit.Verdict;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import io.github.fxjcangku.worldseedlab.core.solver.StartPlacementConstraint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P3.4.1 修改 Salt 的真实 Minecraft 世界生成对照。
 *
 * <p>测试的真实结构起点来自隔离服务端 FULL 区块中保存的 StructureStart NBT，
 * 并非依照数学公式生成的期望坐标，也不是普通客户端读取到的元数据。
 * 测试驱动须事先确认数据包被 Minecraft 自己报告为 enabled。
 *
 * <p>同一控制 Seed 的已生成起点应该符合修改后的 Salt，并拒绝原版 Salt；
 * 不允许把该结论升级为未知服务器的后台真实生成器已识别。
 */
public final class P34ModifiedSaltWorldTest {
    private static final long CONTROL_SEED = 2731L;
    private static final String SET = "minecraft:desert_pyramids";
    private static final int VANILLA_SALT = 14357617;
    private static final int MODIFIED_SALT = 14357844;
    private static final String WORLD = "7".repeat(64);

    private P34ModifiedSaltWorldTest() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 2 && ("26.1.2".equals(args[1]) || "26.2".equals(args[1])),
                "必须明确提供真实Minecraft版本和隔离世界真值目录");
        String version = args[1];
        Path root = Path.of(args[0]);
        String summary = Files.readString(root.resolve("controlled_summary.json"));
        require(summary.contains("\"game_version\": \"" + version + "\"")
                && summary.contains("\"world_seed\": 2731")
                && summary.contains("\"structure_salt\": " + MODIFIED_SALT)
                && summary.contains("\"modified_salt_datapack_enabled\": true")
                && summary.contains("REAL_ENABLED_DATAPACK_MODIFIED_SALT")
                && summary.contains("actual_saved_ServerSide_StructureStart_NBT")
                && summary.contains("actual_FULL_Anvil_block_states")
                && summary.contains("\"not_client_observed\": true"),
                "缺少目标Minecraft真正加载的数据包与FULL结构真值声明");

        var scope = new Scope(WORLD, version, "minecraft:overworld");
        var modified = new RandomSpreadSpec(SET, 32, 8,
                RandomSpreadSpec.Distribution.LINEAR, MODIFIED_SALT);
        var vanilla = new RandomSpreadSpec(SET, 32, 8,
                RandomSpreadSpec.Distribution.LINEAR, VANILLA_SALT);
        List<String> lines = Files.readAllLines(root.resolve("real_structure_starts.csv"));
        require(lines.size() >= 5 && "chunk_x,chunk_z".equals(lines.getFirst()),
                "至少4处不同 Region 的真实 StructureStart 必须由服务端存档提供");
        List<StructureEvidence> evidence = new ArrayList<>();
        Set<String> regions = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            require(evidence.size() < 128, "真实结构Region超出审计预算");
            String[] row = line.split(",", -1);
            require(row.length == 2, "服务端真实结构坐标列数错误");
            int chunkX = Integer.parseInt(row[0]);
            int chunkZ = Integer.parseInt(row[1]);
            String region = Math.floorDiv(chunkX, modified.spacing()) + ":"
                    + Math.floorDiv(chunkZ, modified.spacing());
            require(regions.add(region), "真实结构起点重复Region，不能作为独立证据");
            evidence.add(new StructureEvidence(StartPlacementConstraint.controlledExact(
                    WORLD, version, "minecraft:overworld", SET, chunkX, chunkZ,
                    evidence.size() < 2 ? StartPlacementConstraint.Split.FIT
                            : StartPlacementConstraint.Split.HOLDOUT),
                    StructureProvenance.CONTROL_WORLD_SAVED_START,
                    ChunkHistory.CONFIRMED_SINGLE_EPOCH));
        }
        require(evidence.size() >= 4, "真实修改Salt世界应包含至少4个独立起点");

        var correctlyModified = P34WorldgenCompatibilityAudit.structures(scope, modified,
                evidence, CONTROL_SEED, SaltKnowledge.CONTROL_VERIFIED);
        require(correctlyModified.verdict() == Verdict.MODEL_CONSISTENT
                && correctlyModified.fitMatches() == 2
                && correctlyModified.holdoutMatches() == evidence.size() - 2,
                "修改后的真实结构集必须在2 FIT + 独立HOLDOUT中匹配指定Salt："
                + correctlyModified);

        var wrongVanilla = P34WorldgenCompatibilityAudit.structures(scope, vanilla,
                evidence, CONTROL_SEED, SaltKnowledge.CONTROL_VERIFIED);
        require(wrongVanilla.verdict() == Verdict.MODEL_CONFLICT
                && wrongVanilla.mismatches() >= 1,
                "真实修改Salt的结构不能被错误原版Salt误报为兼容：" + wrongVanilla);
        var unknown = P34WorldgenCompatibilityAudit.structures(scope, vanilla,
                evidence, CONTROL_SEED, SaltKnowledge.UNKNOWN);
        require(unknown.verdict() == Verdict.GENERATOR_UNKNOWN,
                "真实未知Salt输入不得伪称已发现后台模型冲突");
        var sameLow48DifferentHigh16 = P34WorldgenCompatibilityAudit.structures(
                scope, modified, evidence, CONTROL_SEED + (1L << 48),
                SaltKnowledge.CONTROL_VERIFIED);
        require(sameLow48DifferentHigh16.verdict() == Verdict.MODEL_CONSISTENT,
                "RandomSpread真实结构不能给出完整Seed高16位信息");
        var anonymous = P34WorldgenCompatibilityAudit.combine(
                correctlyModified, unknown).chineseSummary();
        require(!anonymous.contains(WORLD) && !anonymous.contains("2731"),
                "审计结果不能公开种子或世界指纹");

        System.out.println("WorldSeedLab P3.4 REAL modified-Salt datapack Minecraft "
                + version + ": " + evidence.size()
                + " actual FULL Anvil StructureStarts match changed Salt=" + MODIFIED_SALT
                + ", original Salt=" + VANILLA_SALT
                + " rejected with mismatches=" + wrongVanilla.mismatches()
                + ", unknown Salt remains GENERATOR_UNKNOWN.");
        System.out.println("来源：隔离受控世界服务端确实加载数据包，真实保存NBT验证；"
                + "非客户端观测、非未知服务器Seed、非真实服Salt认证。");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
