package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.compatibility.P34WorldgenCompatibilityAudit;
import io.github.fxjcangku.worldseedlab.core.compatibility.P34WorldgenCompatibilityAudit.*;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadMath;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import io.github.fxjcangku.worldseedlab.core.solver.StartPlacementConstraint;
import io.github.fxjcangku.worldseedlab.core.solver.VanillaOverworldBiomeModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * P3.4 模型审核防误报回归；客户端CSV夹具源于先前Minecraft FULL区块，
 * 结构位置的纯代码夹具仅测试门控，不能冒充本次真实自定义服务器验收。
 */
public final class P34CompatibilitySmokeTest {
    private static final String HASH = "9".repeat(64);
    private static final String DIM = "minecraft:overworld";
    private static final String SET = "minecraft:desert_pyramids";
    private static final long SEED = 2731L;

    private P34CompatibilitySmokeTest() {}

    public static void main(String[] args) throws Exception {
        String version = System.getProperty("mcVersion", "26.2");
        var scope = new Scope(HASH, version, DIM);
        var spec = new RandomSpreadSpec(SET, 32, 8,
                RandomSpreadSpec.Distribution.LINEAR, 14357617);
        List<StructureEvidence> controls = new ArrayList<>();
        int[][] regions = {{-16, -12}, {11, 21}, {-20, 19}};
        for (int i = 0; i < regions.length; i++) {
            var start = RandomSpreadMath.potentialChunk(
                    SEED, regions[i][0], regions[i][1], spec);
            var entry = StartPlacementConstraint.controlledExact(
                    HASH, version, DIM, SET, start.x(), start.z(),
                    i == 2 ? StartPlacementConstraint.Split.HOLDOUT
                            : StartPlacementConstraint.Split.FIT);
            controls.add(new StructureEvidence(entry,
                    StructureProvenance.CONTROL_WORLD_SAVED_START,
                    ChunkHistory.CONFIRMED_SINGLE_EPOCH));
        }
        var ok = P34WorldgenCompatibilityAudit.structures(
                scope, spec, controls, SEED, SaltKnowledge.CONTROL_VERIFIED);
        require(ok.verdict() == Verdict.MODEL_CONSISTENT
                && ok.fitMatches() == 2 && ok.holdoutMatches() == 1,
                "受控假设中的3个不同Region必须通过");
        var wrongSalt = new RandomSpreadSpec(SET, 32, 8,
                RandomSpreadSpec.Distribution.LINEAR, 14357617 + 907);
        require(P34WorldgenCompatibilityAudit.structures(
                scope, wrongSalt, controls, SEED, SaltKnowledge.CONTROL_VERIFIED)
                .verdict() == Verdict.MODEL_CONFLICT,
                "错误Salt必须产生指定模型冲突，而不是错误宣布Seed不存在");
        require(P34WorldgenCompatibilityAudit.structures(
                scope, spec, controls, SEED, SaltKnowledge.UNKNOWN)
                .verdict() == Verdict.GENERATOR_UNKNOWN, "未知Salt不得升为兼容");
        require(P34WorldgenCompatibilityAudit.structures(
                scope, spec, controls.subList(0, 2), SEED, SaltKnowledge.EXPLICIT_HYPOTHESIS)
                .verdict() == Verdict.INSUFFICIENT_EVIDENCE,
                "没有独立结构留出时不能给出一致");
        List<StructureEvidence> mixed = new ArrayList<>(controls);
        mixed.set(2, new StructureEvidence(mixed.get(2).constraint(),
                StructureProvenance.CONTROL_WORLD_SAVED_START,
                ChunkHistory.MIXED_OR_PREGENERATED));
        require(P34WorldgenCompatibilityAudit.structures(
                scope, spec, mixed, SEED, SaltKnowledge.CONTROL_VERIFIED)
                .verdict() == Verdict.GENERATOR_UNKNOWN,
                "混合历史区块必须拒绝同源推断");
        List<StructureEvidence> artificial = new ArrayList<>(controls);
        artificial.set(2, new StructureEvidence(artificial.get(2).constraint(),
                StructureProvenance.SIGHTING_OR_POSSIBLY_ARTIFICIAL,
                ChunkHistory.CONFIRMED_SINGLE_EPOCH));
        require(P34WorldgenCompatibilityAudit.structures(
                scope, spec, artificial, SEED, SaltKnowledge.CONTROL_VERIFIED)
                .verdict() == Verdict.INSUFFICIENT_EVIDENCE,
                "人工建筑可能的目击记录不作肯定或否定模型证据");
        List<StructureEvidence> duplicate = new ArrayList<>(controls);
        duplicate.add(controls.getFirst());
        reject(() -> P34WorldgenCompatibilityAudit.structures(
                scope, spec, duplicate, SEED, SaltKnowledge.CONTROL_VERIFIED),
                "重复Region应拒绝");
        var wrongScope = new Scope("a".repeat(64), version, DIM);
        reject(() -> P34WorldgenCompatibilityAudit.structures(
                wrongScope, spec, controls, SEED, SaltKnowledge.CONTROL_VERIFIED),
                "跨世界结构不能混用");
        reject(() -> new StructureEvidence(controls.getFirst().constraint(),
                StructureProvenance.CLIENT_CALIBRATED_COMPLETE_PERIMETER,
                ChunkHistory.CONFIRMED_SINGLE_EPOCH),
                "客户端轮廓不能充当StructureStart真值");

        // 真实Minecraft服务端FULL Anvil样本必须保留CONTROL_WORLD_OBSERVED来源。
        List<String> lines = Files.readAllLines(Path.of("src", "test", "resources", "p31",
                "vanilla_" + version + "_seed2731_quart.csv"));
        require(lines.size() == 6
                && "quart_x,quart_y,quart_z,biome_id".equals(lines.getFirst()),
                "真实Minecraft FULL quart公开对照文件缺失");
        var modelScope = new Full64BiomeCandidateSearch.Scope(HASH, version, DIM,
                VanillaOverworldBiomeModel.GENERATOR_PROFILE);
        List<Full64BiomeCandidateSearch.BiomeSample> fullChunks = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String[] row = lines.get(i).split(",", -1);
            fullChunks.add(new Full64BiomeCandidateSearch.BiomeSample(
                    modelScope, Integer.parseInt(row[0]), Integer.parseInt(row[1]),
                    Integer.parseInt(row[2]), row[3],
                    i <= 3 ? Full64BiomeCandidateSearch.Split.FIT
                            : Full64BiomeCandidateSearch.Split.HOLDOUT,
                    Full64BiomeCandidateSearch.Origin.CONTROL_WORLD_OBSERVED));
        }
        var model = VanillaOverworldBiomeModel.create(modelScope);
        var compatible = P34WorldgenCompatibilityAudit.controlledWorldBiomes(scope, fullChunks,
                model, SEED, BiomeKnowledge.CONTROL_VANILLA_VERIFIED,
                ChunkHistory.CONFIRMED_SINGLE_EPOCH);
        require(compatible.verdict() == Verdict.MODEL_CONSISTENT
                && compatible.fitMatches() == 3 && compatible.holdoutMatches() == 2,
                "真实Minecraft FULL观测必须与真Seed原版气候模型相符");
        var conflict = P34WorldgenCompatibilityAudit.controlledWorldBiomes(scope, fullChunks,
                model, SEED + (1L << 48), BiomeKnowledge.EXPLICIT_VANILLA_HYPOTHESIS,
                ChunkHistory.CONFIRMED_SINGLE_EPOCH);
        require(conflict.verdict() == Verdict.MODEL_CONFLICT,
                "错误高16位必须与真实群系矛盾");
        var unknown = P34WorldgenCompatibilityAudit.controlledWorldBiomes(scope, fullChunks,
                null, SEED, BiomeKnowledge.UNKNOWN_OR_CUSTOM,
                ChunkHistory.CONFIRMED_SINGLE_EPOCH);
        require(unknown.verdict() == Verdict.GENERATOR_UNKNOWN,
                "未知/自定义生成器不能隐式计算原版群系");
        require(P34WorldgenCompatibilityAudit.controlledWorldBiomes(scope, fullChunks,
                null, SEED, BiomeKnowledge.EXPLICIT_VANILLA_HYPOTHESIS,
                ChunkHistory.UNKNOWN).verdict() == Verdict.GENERATOR_UNKNOWN,
                "同一服务端中的旧区块风险必须保守处理");
        reject(() -> P34WorldgenCompatibilityAudit.controlledWorldBiomes(wrongScope,
                fullChunks, model, SEED, BiomeKnowledge.CONTROL_VANILLA_VERIFIED,
                ChunkHistory.CONFIRMED_SINGLE_EPOCH), "跨世界FULL群系必须拒绝");
        List<Full64BiomeCandidateSearch.BiomeSample> spoofed = new ArrayList<>(fullChunks);
        var first = spoofed.getFirst();
        spoofed.set(0, new Full64BiomeCandidateSearch.BiomeSample(
                modelScope, first.quartX(), first.quartY(), first.quartZ(),
                first.observedBiome(), first.split(),
                Full64BiomeCandidateSearch.Origin.CLIENT_OBSERVED));
        reject(() -> P34WorldgenCompatibilityAudit.controlledWorldBiomes(scope,
                spoofed, model, SEED, BiomeKnowledge.CONTROL_VANILLA_VERIFIED,
                ChunkHistory.CONFIRMED_SINGLE_EPOCH),
                "服务端FULL区块和普通客户端证据来源不能互换");
        require(P34WorldgenCompatibilityAudit.combine(ok, compatible).overall()
                == Verdict.MODEL_CONSISTENT, "两个证据轴均符合时才有一致结论");
        var report = P34WorldgenCompatibilityAudit.combine(ok, compatible).chineseSummary();
        require(!report.contains(HASH) && !report.contains("2731") && !report.contains("-14000")
                && report.contains("仅对输入模型"), "匿名报告不能包含世界/Seed/坐标");
        require(P34WorldgenCompatibilityAudit.combine(ok, unknown).overall()
                == Verdict.GENERATOR_UNKNOWN, "未知生成器不能被结构匹配洗白");
        require(P34WorldgenCompatibilityAudit.combine(ok, conflict).overall()
                == Verdict.MODEL_CONFLICT, "真实群系冲突不能被结构匹配掩盖");

        System.out.println("P3.4 " + version
                + ": 结构Salt/生成时期/人工建筑/Region去重防误报与真实FULL quart"
                + " 原版模型正反例通过；输入是公开受控世界，不是未知服务端Seed。");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void reject(Runnable runnable, String message) {
        try {
            runnable.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // 拒绝非法世界混合正是被测试的行为。
        }
    }
}
