package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.Full64High16ShardPlan;
import io.github.fxjcangku.worldseedlab.core.solver.LocalSmtSeedSolver;
import io.github.fxjcangku.worldseedlab.core.solver.VanillaOverworldBiomeModel;
import java.nio.file.Path;
import java.util.List;

/**
 * P3.1 分片边界、负索引与原版真实存档群系正反例。
 * 在双版本标准 check 中执行几项，完整65536高位由独立 Actions 分片任务验收。
 */
public final class P31Full64ShardSmokeTest {
    private P31Full64ShardSmokeTest() {}

    public static void main(String[] args) throws Exception {
        for (int candidates : new int[] {1, 2, 8, 32}) {
            long cursor = 0, counted = 0;
            for (int shards : new int[] {1, 7, 16, 32, 64, 256}) {
                cursor = 0;
                counted = 0;
                for (int index = 0; index < shards; index++) {
                    var shard = Full64High16ShardPlan.partition(candidates, index, shards);
                    check(shard.fromIndex() == cursor, "分片之间不能有缺口、重叠或倒序");
                    check(shard.shardCount() == shards && shard.shardIndex() == index,
                            "分片身份必须与索引相同");
                    var budget = shard.budget(60_000, 20);
                    check(budget.fromIndexInclusive() == shard.fromIndex()
                            && budget.toIndexExclusive() == shard.endExclusive()
                            && budget.maximumEvaluations() == shard.length(),
                            "精确搜索预算不能遗漏窗口末尾");
                    counted += shard.length();
                    cursor = shard.endExclusive();
                }
                check(cursor == (long) candidates * Full64BiomeCandidateSearch.HIGH16_COUNT
                        && counted == cursor, "必须穷尽完整高16候选域");
            }
        }
        fail(() -> Full64High16ShardPlan.partition(1, 16, 16), "拒绝越界分片索引");
        fail(() -> Full64High16ShardPlan.partition(1, -1, 16), "拒绝负分片索引");
        fail(() -> Full64High16ShardPlan.partition(0, 0, 16), "拒绝无种子分片");
        fail(() -> Full64High16ShardPlan.partition(1, 0, 257), "拒绝过多分片");

        String version = System.getProperty("mcVersion", "26.2");
        var scope = new Full64BiomeCandidateSearch.Scope(
                "e".repeat(64), version, "minecraft:overworld",
                VanillaOverworldBiomeModel.GENERATOR_PROFILE);
        Path file = Path.of("src", "test", "resources", "p31",
                "vanilla_" + version + "_seed2731_quart.csv");
        var actualSavedSamples = P31Full64ShardRunner.readControlledSamples(file, scope);
        var truth = VanillaOverworldBiomeModel.create(scope);
        var outcome = Full64BiomeCandidateSearch.search(
                List.of(new LocalSmtSeedSolver.VerifiedLow48(2731L, 2, 1)),
                actualSavedSamples, truth,
                new Full64BiomeCandidateSearch.SearchBudget(0, 2, 2, 120_000, 8),
                () -> false);
        check(outcome.completion()
                        == Full64BiomeCandidateSearch.Completion.REQUESTED_WINDOW_COMPLETE
                && outcome.evaluations() == 2
                && outcome.candidates().size() == 1
                && outcome.candidates().getFirst().fullSeedBits() == 2731L
                && outcome.candidates().getFirst().holdoutMatches() == 2,
                "仅FULL存档群系支持Seed=2731且排除high16=1");

        System.out.println("WorldSeedLab P3.1 partition continuity + actual Vanilla quart "
                + version + " positive/negative reference passed.");
    }

    private static void check(boolean truth, String description) {
        if (!truth) throw new AssertionError(description);
    }

    private static void fail(Runnable action, String description) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(description);
    }
}
