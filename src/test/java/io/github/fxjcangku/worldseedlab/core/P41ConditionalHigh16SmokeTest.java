package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.ClientQuartEvidenceArchive;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.Full64High16ShardPlan;
import io.github.fxjcangku.worldseedlab.core.solver.LocalSmtSeedSolver;
import io.github.fxjcangku.worldseedlab.core.solver.P40CustomClimateModel;
import io.github.fxjcangku.worldseedlab.core.solver.P41ConditionalHigh16Search;
import io.github.fxjcangku.worldseedlab.core.solver.VanillaOverworldBiomeModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * P4.1 两版真实Minecraft FULL存档群系与目标版本Mojmap前向噪声校验。
 * 结构低48使用受控已知真起点回归的2731，不能冒充未知服务器已破解。
 * 外部自定义噪声映射未知，以下仅测试条件模型下的高位枚举和续算。
 */
public final class P41ConditionalHigh16SmokeTest {
    private P41ConditionalHigh16SmokeTest() {}

    public static void main(String[] args) throws Exception {
        String version = System.getProperty("mcVersion", "26.2");
        String world = "c".repeat(64);
        var session = new ClientQuartEvidenceArchive.Session(
                world, version, "minecraft:overworld");
        var original = Files.readAllLines(Path.of("src", "test", "resources", "p31",
                "vanilla_" + version + "_seed2731_quart.csv"));
        check(original.size() == 6, "真实FULL存档quart必须包含5个独立样本");
        var scope = new Full64BiomeCandidateSearch.Scope(
                world, version, "minecraft:overworld",
                VanillaOverworldBiomeModel.GENERATOR_PROFILE);
        var originalSampler = VanillaOverworldBiomeModel.create(scope).prepare(2731L);
        for (var line : original.subList(1, original.size())) {
            String[] fields = line.split(",", -1);
            int x = Integer.parseInt(fields[0]);
            int y = Integer.parseInt(fields[1]);
            int z = Integer.parseInt(fields[2]);
            String biome = fields[3];
            check(biome.equals(originalSampler.biomeAtQuart(x, y, z)),
                    "真实Minecraft FULL存档与独立生成器计算不一致");
            session.addQuart(x, y, z, biome);
        }
        var archive = session.snapshot();
        var spec = P40CustomClimateModel.controlledVanillaPreset(
                "worldseedlab:p41_controlled_overworld");
        var low = List.of(new LocalSmtSeedSolver.VerifiedLow48(2731L, 3, 1));

        var full = P41ConditionalHigh16Search.search(low, archive, spec,
                new Full64BiomeCandidateSearch.SearchBudget(0, 8, 8, 120_000, 16),
                () -> false);
        check(full.completion()
                        == P41ConditionalHigh16Search.Completion.REQUESTED_WINDOW_COMPLETE,
                "八个受控模型搜索必须完整结束");
        check(full.evaluations() == 8 && full.nextIndex() == 8
                        && full.domainSize() == 65536,
                "不能跳过模型、伪造已检查数或将前8个高位当成全部65536");
        check(full.candidates().size() == 1
                        && full.candidates().getFirst().fullSeedBits() == 2731L
                        && full.candidates().getFirst().fitMatches() == 3
                        && full.candidates().getFirst().holdoutMatches() == 2,
                "真正FULL世界群系应仅保留正确受控Seed 2731");
        check(!full.fullConditionalDomainChecked(
                        new Full64BiomeCandidateSearch.SearchBudget(0, 8, 8, 120_000, 16)),
                "8/65536不能声称全高位域条件完成");

        var limited = P41ConditionalHigh16Search.search(low, archive, spec,
                new Full64BiomeCandidateSearch.SearchBudget(0, 8, 2, 120_000, 16),
                () -> false);
        check(limited.completion()
                        == P41ConditionalHigh16Search.Completion.EVALUATION_BUDGET
                        && limited.nextIndex() == 2 && limited.evaluations() == 2,
                "预算耗尽必须精确返回未检查的第一个索引");
        var resumed = P41ConditionalHigh16Search.search(low, archive, spec,
                new Full64BiomeCandidateSearch.SearchBudget(
                        limited.nextIndex(), 8, 6, 120_000, 16),
                () -> false);
        check(resumed.completion()
                        == P41ConditionalHigh16Search.Completion.REQUESTED_WINDOW_COMPLETE
                        && resumed.nextIndex() == 8 && resumed.evaluations() == 6
                        && limited.candidates().size() + resumed.candidates().size()
                        == full.candidates().size(),
                "断点续算必须无重复无遗漏，候选集合与单轮一致");
        var cancelled = P41ConditionalHigh16Search.search(low, archive, spec,
                new Full64BiomeCandidateSearch.SearchBudget(0, 4, 4, 120_000, 16),
                () -> true);
        check(cancelled.completion() == P41ConditionalHigh16Search.Completion.CANCELLED
                        && cancelled.evaluations() == 0 && cancelled.nextIndex() == 0,
                "取消必须在消耗算力前失败关闭");

        var blocked = new P40CustomClimateModel.Specification(
                "worldseedlab:p41_wrong_biomes", P40CustomClimateModel.SAMPLER,
                spec.entries().stream()
                        .map(e -> com.mojang.datafixers.util.Pair.of(
                                e.getFirst(), "worldseedlab:invalid_biome"))
                        .toList(),
                Set.of("worldseedlab:invalid_biome"));
        var incompatible = P41ConditionalHigh16Search.search(low, archive, blocked,
                new Full64BiomeCandidateSearch.SearchBudget(0, 4, 4, 120_000, 16),
                () -> false);
        check(incompatible.completion()
                        == P41ConditionalHigh16Search.Completion.MODEL_REGISTRY_INCOMPATIBLE
                        && incompatible.evaluations() == 0
                        && incompatible.candidates().isEmpty(),
                "气候表无法解释真实FULL群系时不能执行假种子枚举");

        expectFailure(() -> P41ConditionalHigh16Search.search(
                List.of(new LocalSmtSeedSolver.VerifiedLow48(2731L, 1, 0)),
                archive, spec, new Full64BiomeCandidateSearch.SearchBudget(
                        0, 4, 4, 120_000, 16), () -> false));
        expectFailure(() -> P41ConditionalHigh16Search.search(
                List.of(low.getFirst(), low.getFirst()),
                archive, spec, new Full64BiomeCandidateSearch.SearchBudget(
                        0, 4, 4, 120_000, 16), () -> false));
        expectFailure(() -> P41ConditionalHigh16Search.search(low, archive, spec,
                new Full64BiomeCandidateSearch.SearchBudget(
                        0, 65537, 1, 120_000, 16), () -> false));

        long previous = 0;
        for (int shard = 0; shard < 16; shard++) {
            var partition = Full64High16ShardPlan.partition(1, shard, 16);
            check(partition.fromIndex() == previous, "P4.1分片之间不能重叠或遗漏");
            previous = partition.endExclusive();
        }
        check(previous == 65536, "高16分片必须覆盖全部65536个值");
        System.out.println("P4.1 real Minecraft FULL quart truth " + version
                + " PASS: trueSeed=2731, wrong high16 excluded, 8 candidates,"
                + " exact resume/cancel/boundary/registry/split verified;"
                + " MODEL_UNVERIFIED; no unknown custom server Seed recovery.");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void expectFailure(Runnable task) {
        try { task.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("非法low48或搜索域未被拒绝");
    }
}
