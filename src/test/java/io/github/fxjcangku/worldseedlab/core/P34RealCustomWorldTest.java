package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.compatibility.P34WorldgenCompatibilityAudit;
import io.github.fxjcangku.worldseedlab.core.compatibility.P34WorldgenCompatibilityAudit.*;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import io.github.fxjcangku.worldseedlab.core.solver.StartPlacementConstraint;
import io.github.fxjcangku.worldseedlab.core.solver.VanillaOverworldBiomeModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P3.4 真实 Minecraft 自定义地形服务器产生的保存区块取证回放。
 *
 * <p>脚本已经启动了目标版本服务端，并用完整 Anvil 区块验证 sandstone/sand 层、
 * 沙漠群系以及 StructureStart NBT；本测试逐项验证：
 * 结构 RandomSpread 不受地形改变，原版 Overworld MultiNoise 与自定义单群系
 * 生成器不兼容。这里的真值来自受控服务端存档，不可冒充 CLIENT_OBSERVED。
 */
public final class P34RealCustomWorldTest {
    private static final long CONTROL_SEED = 2731L;
    private static final String DIM = "minecraft:overworld";
    private static final String SET = "minecraft:desert_pyramids";
    private static final String HASH = "d".repeat(64);

    private P34RealCustomWorldTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || (!"26.1.2".equals(args[1]) && !"26.2".equals(args[1]))) {
            throw new IllegalArgumentException("用法: 受控真实世界目录 Minecraft版本");
        }
        String version = args[1];
        Path root = Path.of(args[0]);
        String manifest = Files.readString(root.resolve("controlled_summary.json"));
        require(manifest.contains("actual_minecraft_flat_generator_with_desert_pyramids")
                && manifest.contains("actual_FULL_Anvil_block_states")
                && manifest.contains("actual_saved_ServerSide_StructureStart_NBT")
                && manifest.contains("\"not_client_observed\": true"),
                "必须使用真实Minecraft自定义地层、保存起点和FULL群系真值");

        var scope = new Scope(HASH, version, DIM);
        var spec = new RandomSpreadSpec(SET, 32, 8,
                RandomSpreadSpec.Distribution.LINEAR, 14357617);
        var rows = Files.readAllLines(root.resolve("real_structure_starts.csv"));
        require(rows.size() >= 4 && "chunk_x,chunk_z".equals(rows.getFirst()),
                "没有真正生成的结构起点FULL/NBT证据");
        List<StructureEvidence> structural = new ArrayList<>();
        Set<String> regions = new HashSet<>();
        for (int i = 1; i < rows.size() && structural.size() < 5; i++) {
            var tokens = rows.get(i).split(",", -1);
            require(tokens.length == 2, "真实结构起点列数错误");
            int cx = Integer.parseInt(tokens[0]);
            int cz = Integer.parseInt(tokens[1]);
            require(regions.add(Math.floorDiv(cx, spec.spacing()) + ":"
                    + Math.floorDiv(cz, spec.spacing())), "受控结构区域重复");
            structural.add(new StructureEvidence(StartPlacementConstraint.controlledExact(
                    HASH, version, DIM, SET, cx, cz,
                    structural.size() < 2 ? StartPlacementConstraint.Split.FIT
                            : StartPlacementConstraint.Split.HOLDOUT),
                    StructureProvenance.CONTROL_WORLD_SAVED_START,
                    ChunkHistory.CONFIRMED_SINGLE_EPOCH));
        }
        var consistent = P34WorldgenCompatibilityAudit.structures(scope, spec,
                structural, CONTROL_SEED, SaltKnowledge.CONTROL_VERIFIED);
        require(consistent.verdict() == Verdict.MODEL_CONSISTENT,
                "真实自定义地形中的原版神殿StructureStart必须遵守RandomSpread；"
                + consistent);
        require(P34WorldgenCompatibilityAudit.structures(scope, spec, structural,
                CONTROL_SEED + (1L << 48), SaltKnowledge.CONTROL_VERIFIED).verdict()
                == Verdict.MODEL_CONSISTENT, "结构随机放置不能区分完整Seed高16位");
        var differentSalt = new RandomSpreadSpec(SET, 32, 8,
                RandomSpreadSpec.Distribution.LINEAR, 14357617 + 227);
        require(P34WorldgenCompatibilityAudit.structures(scope, differentSalt,
                structural, CONTROL_SEED, SaltKnowledge.EXPLICIT_HYPOTHESIS).verdict()
                == Verdict.MODEL_CONFLICT, "错误结构Salt必须拒绝原版位置模型");

        var quartRows = Files.readAllLines(root.resolve("real_custom_quart.csv"));
        require(quartRows.size() == 6 && "quart_x,quart_y,quart_z,biome_id"
                .equals(quartRows.getFirst()), "必须有5个完整独立区块群系");
        var biomeScope = new Full64BiomeCandidateSearch.Scope(
                HASH, version, DIM, VanillaOverworldBiomeModel.GENERATOR_PROFILE);
        List<Full64BiomeCandidateSearch.BiomeSample> fullWorld = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String[] cells = quartRows.get(i).split(",", -1);
            require(cells.length == 4 && "minecraft:desert".equals(cells[3]),
                    "自定义群系必须来自FULL实际区块，不是配置文件声明");
            fullWorld.add(new Full64BiomeCandidateSearch.BiomeSample(
                    biomeScope, Integer.parseInt(cells[0]), Integer.parseInt(cells[1]),
                    Integer.parseInt(cells[2]), cells[3],
                    i <= 3 ? Full64BiomeCandidateSearch.Split.FIT
                            : Full64BiomeCandidateSearch.Split.HOLDOUT,
                    Full64BiomeCandidateSearch.Origin.CONTROL_WORLD_OBSERVED));
        }
        var model = VanillaOverworldBiomeModel.create(biomeScope);
        var biomeConflict = P34WorldgenCompatibilityAudit.controlledWorldBiomes(
                scope, fullWorld, model, CONTROL_SEED,
                BiomeKnowledge.EXPLICIT_VANILLA_HYPOTHESIS,
                ChunkHistory.CONFIRMED_SINGLE_EPOCH);
        require(biomeConflict.verdict() == Verdict.MODEL_CONFLICT,
                "真实单群系自定义地形不能用原版MultiNoise验证高16；"
                + biomeConflict);
        var unknownGenerator = P34WorldgenCompatibilityAudit.controlledWorldBiomes(
                scope, fullWorld, null, CONTROL_SEED, BiomeKnowledge.UNKNOWN_OR_CUSTOM,
                ChunkHistory.CONFIRMED_SINGLE_EPOCH);
        require(unknownGenerator.verdict() == Verdict.GENERATOR_UNKNOWN,
                "不应默认为自定义世界采用原版生成器");
        var combined = P34WorldgenCompatibilityAudit.combine(consistent, biomeConflict);
        require(combined.overall() == Verdict.MODEL_CONFLICT,
                "结构符合时，不能掩盖自定义群系与原版模型的冲突");
        require(!combined.chineseSummary().contains(HASH)
                && !combined.chineseSummary().contains("2731"),
                "匿名结论不能泄漏种子/指纹");

        System.out.println("WorldSeedLab P3.4 REAL custom-flat Minecraft " + version
                + ": saved StructureStart obeys vanilla RandomSpread, "
                + "saved FULL custom-desert biomes reject Vanilla MultiNoise; "
                + "wrong Salt, unknown generator, high16-insensitive structure verified.");
        System.out.println("证据来源：受控服务器真实 Anvil/NBT；不是普通 Fabric 客户端群系或轮廓。");
    }

    private static void require(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
}
