package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.BedrockRandomModel;
import io.github.fxjcangku.worldseedlab.core.solver.BedrockRandomModel.Algorithm;
import io.github.fxjcangku.worldseedlab.core.solver.BedrockRandomModel.GradientRule;
import io.github.fxjcangku.worldseedlab.core.solver.BedrockRandomModel.PositiveBlock;
import io.github.fxjcangku.worldseedlab.core.solver.BedrockHigh16Search;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.LegacyLcg48;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * P4.4 原创基岩随机分支与两版实际 Mojmap RandomState 的独立对照。
 *
 * <p>这是原生随机类/命名工厂/梯度浮点语义验收；未生成真实世界、未采客户端
 * 方块，绝不能当作真实基岩自然来源或全 48/64 位逆解成功。公开控制 Seed
 * 只用于测试；任何私人世界坐标、存档和服务端连接均不进入本测试。
 */
public final class P44BedrockMinecraftParityTest {
    private static final long[] SEEDS = {
            0L, 1L, -1L, 2731L, 0xBEEF000000000AABL, Long.MIN_VALUE, Long.MAX_VALUE
    };
    private static final String[] NAMES = {
            "minecraft:bedrock_floor", "minecraft:bedrock_roof", "worldseedlab:bedrock_negative"
    };
    private static final int[] X_COORDINATES = {
            0, 1, -1, 16, -16, 30_000_000, -30_000_000, Integer.MIN_VALUE, Integer.MAX_VALUE
    };

    private P44BedrockMinecraftParityTest() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registries = VanillaRegistries.createLookup();
        var noiseSettings = registries.lookupOrThrow(Registries.NOISE_SETTINGS);
        var noises = registries.lookupOrThrow(Registries.NOISE);
        int inspected = 0;
        for (Algorithm algorithm : Algorithm.values()) {
            var key = algorithm == Algorithm.LEGACY
                    ? NoiseGeneratorSettings.NETHER : NoiseGeneratorSettings.OVERWORLD;
            var settings = noiseSettings.getOrThrow(key).value();
            check(settings.useLegacyRandomSource() == (algorithm == Algorithm.LEGACY),
                    "目标版本原版下界/主世界随机算法与声明模型不同");
            for (long seed : SEEDS) {
                // 实际 RandomState 是主预言机：不只检查手写的 fork 链自洽。
                RandomState state = RandomState.create(settings, noises, seed);
                for (String name : NAMES) {
                    PositionalRandomFactory factory = state.getOrCreateRandomFactory(Identifier.parse(name));
                    PositionalRandomFactory direct = nativeSource(algorithm, seed)
                            .forkPositional().fromHashOf(name).forkPositional();
                    for (int x : X_COORDINATES) {
                        int z = x * 31 + 7919;
                        for (int y = -63; y <= -60; y++) {
                            check(BedrockRandomModel.coordinateSeed(x, y, z) == Mth.getSeed(x, y, z),
                                    "坐标散列的 int/long 溢出或符号右移不一致");
                            float expected = factory.at(x, y, z).nextFloat();
                            float chain = direct.at(x, y, z).nextFloat();
                            int numerator = BedrockRandomModel.nextFloat24(seed, algorithm, name, x, y, z);
                            float actual = (float) numerator * 0x1.0p-24F;
                            check(Float.floatToRawIntBits(expected) == Float.floatToRawIntBits(chain),
                                    "官方 RandomState 与直接命名工厂链不同");
                            check(Float.floatToRawIntBits(expected) == Float.floatToRawIntBits(actual),
                                    "原创基岩模型与实际 Minecraft nextFloat 不一致");
                            if (algorithm == Algorithm.LEGACY) {
                                long named = BedrockRandomModel.legacyNamedState(seed, name);
                                check(numerator == BedrockRandomModel.legacyNextFloat24(named, x, y, z),
                                        "Legacy 命名工厂低 48 位回验不一致");
                            }
                            inspected++;
                        }
                    }
                }
            }
        }
        verifyGradientBoundaries();
        verifyPositiveOnlyModel();
        verifyLegacyInverse();
        verifyHigh16AndFailures();
        verifyCompleteHigh16Search();
        System.out.println("P4.4 真实 Minecraft " + System.getProperty("mcVersion", "未指定版本")
                + " RandomState 基岩随机分支对照通过：" + inspected
                + " 项；Legacy 两层全部前像、梯度边界、正负Seed各65,536高位和失败保护通过。"
                + "本结果不代表真实世界方块取证或完整种子域破解。");
    }

    private static void verifyGradientBoundaries() {
        var floor = new GradientRule(NAMES[0], -64, -59, false);
        int[] expectedThresholds = {16_777_216, 13_421_773, 10_066_330, 6_710_887, 3_355_444, 0};
        for (int i = 0; i < expectedThresholds.length; i++) {
            check(BedrockRandomModel.threshold24(floor, i - 64) == expectedThresholds[i],
                    "原版五层基岩梯度整数边界错误");
        }
        for (GradientRule rule : new GradientRule[] {
                floor, new GradientRule(NAMES[1], 122, 127, true),
                new GradientRule(NAMES[0], -100, -93, false),
                new GradientRule(NAMES[0], Integer.MIN_VALUE, Integer.MAX_VALUE, false)
        }) {
            int[] positions = rule.trueAtAndBelow() == Integer.MIN_VALUE
                    ? new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}
                    : new int[] {rule.trueAtAndBelow() - 1, rule.trueAtAndBelow(),
                            rule.trueAtAndBelow() + 1, rule.falseAtAndAbove() - 1,
                            rule.falseAtAndAbove(), rule.falseAtAndAbove() + 1};
            for (int y : positions) {
                int threshold = BedrockRandomModel.threshold24(rule, y);
                for (int value : new int[] {0, Math.max(0, threshold - 1),
                        Math.min(BedrockRandomModel.FLOAT_DOMAIN - 1, threshold),
                        BedrockRandomModel.FLOAT_DOMAIN - 1}) {
                    boolean expected = nativeGradient((float) value * 0x1.0p-24F, rule, y);
                    check((value < threshold) == expected, "原生 double 梯度与 float 比较边界不同");
                }
                PositiveBlock block = new PositiveBlock(-129, y, 257);
                for (Algorithm algorithm : Algorithm.values()) {
                    float random = nativeSource(algorithm, -1L).forkPositional()
                            .fromHashOf(rule.randomName()).forkPositional()
                            .at(block.x(), y, block.z()).nextFloat();
                    boolean expected = nativeGradient(random, rule, y) != rule.inverted();
                    check(BedrockRandomModel.predictsBedrock(-1L, algorithm, rule, block) == expected,
                            "顶部反转、确定层或自定义锚点不匹配");
                }
            }
        }
    }

    private static void verifyPositiveOnlyModel() {
        var rule = new GradientRule(NAMES[0], -64, -59, false);
        for (Algorithm algorithm : Algorithm.values()) {
            var nativeFactory = nativeSource(algorithm, 2731L).forkPositional()
                    .fromHashOf(NAMES[0]).forkPositional();
            List<PositiveBlock> positives = new ArrayList<>();
            PositiveBlock incompatible = null;
            for (int i = 0; i < 256; i++) {
                PositiveBlock point = new PositiveBlock(i * 1613 - 213_000, -62, i * 3719 + 191_000);
                float value = nativeFactory.at(point.x(), point.y(), point.z()).nextFloat();
                if (nativeGradient(value, rule, point.y())) positives.add(point);
                else incompatible = point;
            }
            // 这些标签来自原生随机控制值，不是 CLIENT_OBSERVED 世界方块。
            check(!positives.isEmpty() && incompatible != null, "随机控制夹具应包含两类分支");
            check(BedrockRandomModel.matchesPositive(2731L, algorithm, rule, positives),
                    "真实随机控制值中的阳性条件被拒绝");
            check(!BedrockRandomModel.matchesPositive(2732L, algorithm, rule, positives),
                    "错误 Seed 未被独立原生随机控制样本排除");
            List<PositiveBlock> fabricated = new ArrayList<>(positives);
            fabricated.add(incompatible);
            check(!BedrockRandomModel.matchesPositive(2731L, algorithm, rule, fabricated),
                    "伪造的阳性基岩条件未被拒绝");
        }
    }

    private static void verifyLegacyInverse() {
        boolean sawSignedSecond = false;
        boolean sawMultiplePreimages = false;
        for (long seed : SEEDS) {
            LegacyRandomSource nativeRandom = new LegacyRandomSource(seed);
            long actualOutput = nativeRandom.nextLong();
            sawSignedSecond |= (int) actualOutput < 0;
            var internalStates = BedrockRandomModel.reverseLegacyNextLongLow48(actualOutput & LegacyLcg48.MASK);
            long expectedInternal = (seed ^ LegacyLcg48.MULTIPLIER) & LegacyLcg48.MASK;
            check(internalStates.contains(expectedInternal), "nextLong 逆向遗漏真实前置内部状态");
            sawMultiplePreimages |= internalStates.size() > 1;
            for (long internal : internalStates) {
                long replay = new LegacyRandomSource(internal ^ LegacyLcg48.MULTIPLIER).nextLong();
                check((replay & LegacyLcg48.MASK) == (actualOutput & LegacyLcg48.MASK),
                        "nextLong 前像未通过原生随机源复核");
            }
            for (String name : NAMES) {
                long named = BedrockRandomModel.legacyNamedState(seed, name);
                List<Long> recovered = BedrockRandomModel.recoverLegacyWorldLow48(named, name);
                check(recovered.contains(seed & LegacyLcg48.MASK), "两层逆向遗漏世界 low48");
                long previous = -1;
                for (long candidate : recovered) {
                    check(candidate > previous && candidate <= LegacyLcg48.MASK,
                            "世界 low48 前像未去重排序或越界");
                    previous = candidate;
                    var factory = new LegacyRandomSource(candidate).forkPositional()
                            .fromHashOf(name).forkPositional();
                    for (int x : new int[] {-120_003, 0, 217_789}) {
                        int expected = (int) (factory.at(x, -62, x * 7).nextFloat() * 0x1.0p24F);
                        check(BedrockRandomModel.legacyNextFloat24(named, x, -62, x * 7) == expected,
                                "两层逆向候选的原生命名位置工厂不一致");
                    }
                }
            }
        }
        check(sawSignedSecond, "缺少 nextLong 第二个 int 为负的借位回归");
        check(sawMultiplePreimages, "缺少 nextLong 低 48 位具有多个前像的回归");
    }

    private static void verifyHigh16AndFailures() {
        boolean high16ChangesXoroshiro = false;
        for (int x = 0; x < 64; x++) {
            for (Algorithm algorithm : Algorithm.values()) {
                int low = BedrockRandomModel.nextFloat24(2731L, algorithm, NAMES[0], x, -62, -x);
                int high = BedrockRandomModel.nextFloat24(0xBEEF000000000AABL,
                        algorithm, NAMES[0], x, -62, -x);
                if (algorithm == Algorithm.LEGACY) check(low == high, "Legacy 不应泄露世界高 16 位");
                else high16ChangesXoroshiro |= low != high;
            }
        }
        check(high16ChangesXoroshiro, "Xoroshiro 高 16 位敏感性控制失败");
        check(NAMES[2].hashCode() < 0, "应覆盖命名 hashCode 为负的符号扩展");
        expectIllegal(() -> new GradientRule("bedrock_floor", -64, -59, false));
        expectIllegal(() -> new GradientRule(NAMES[0], 0, 0, false));
        expectIllegal(() -> BedrockRandomModel.reverseLegacyNextLongLow48(-1));
        expectIllegal(() -> BedrockRandomModel.recoverLegacyWorldLow48(1L << 48, NAMES[0]));
        expectIllegal(() -> BedrockRandomModel.matchesPositive(0, Algorithm.LEGACY,
                new GradientRule(NAMES[0], -64, -59, false), List.of()));
        Thread.currentThread().interrupt();
        try {
            BedrockRandomModel.reverseLegacyNextLongLow48(0);
            throw new AssertionError("逆向取消后不应返回部分前像");
        } catch (CancellationException expected) {
            // 取消必须显式失败，不能把未扫描完的种子域当作空集或已完成。
        } finally {
            Thread.interrupted();
        }
    }

    private static boolean nativeGradient(float random, GradientRule rule, int y) {
        if (y <= rule.trueAtAndBelow()) return true;
        if (y >= rule.falseAtAndAbove()) return false;
        // 必须强制选择 DDDDD 重载，与原生 VerticalGradientCondition.compute 一致。
        double probability = Mth.map((double) y, (double) rule.trueAtAndBelow(),
                (double) rule.falseAtAndAbove(), 1.0D, 0.0D);
        return (double) random < probability;
    }

    /**
     * 从Minecraft原生随机类生成独立控制条件，完整覆盖高16，包含Java nextLong无法
     * 生成的人工指定负Seed。这里仍是随机分支控制，不冒充已生成FULL区块的方块证据。
     */
    private static void verifyCompleteHigh16Search() {
        var rule = new GradientRule(NAMES[0], -64, -59, false);
        long[] fullSeeds = {2731L, 0xBEEF000000000AABL};
        boolean coveredUnreachableRandomSeed = false;
        for (long seed : fullSeeds) {
            var factory = new XoroshiroRandomSource(seed).forkPositional()
                    .fromHashOf(NAMES[0]).forkPositional();
            var fit = nativePositives(factory, rule, 0, 96);
            var holdout = nativePositives(factory, rule, 1024, 32);
            long low48 = seed & LegacyLcg48.MASK;
            var budget = new Full64BiomeCandidateSearch.SearchBudget(0, 65_536, 65_536, 30_000, 16);
            var result = BedrockHigh16Search.search(low48, rule, fit, holdout, budget, () -> false);
            check(result.completeHigh16Domain(budget) && result.evaluations() == 65_536,
                    "主世界基岩高16必须真正完整覆盖65,536个值");
            check(result.candidates().size() == 1 && result.candidates().getFirst().fullSeedBits() == seed,
                    "原生随机条件下没有恢复唯一控制Seed");
            // 对照常见错误回退：低48的nextLong前像产生的完整输出并不覆盖任意高16。
            boolean randomReachable = BedrockRandomModel.reverseLegacyNextLongLow48(low48).stream()
                    .anyMatch(internal -> new LegacyRandomSource(internal ^ LegacyLcg48.MULTIPLIER)
                            .nextLong() == seed);
            coveredUnreachableRandomSeed |= !randomReachable && seed < 0;

            int high = (int) (seed >>> 48);
            var one = new Full64BiomeCandidateSearch.SearchBudget(high, high + 1L, 1, 30_000, 16);
            var wrongHoldout = new ArrayList<>(holdout);
            wrongHoldout.set(0, nativeNegative(factory, rule, 4096));
            var rejected = BedrockHigh16Search.search(low48, rule, fit, wrongHoldout, one, () -> false);
            check(rejected.candidates().isEmpty() && rejected.holdoutRejected() == 1,
                    "独立错误HOLDOUT必须否决通过FIT的Seed");

            var firstBudget = new Full64BiomeCandidateSearch.SearchBudget(0, 65_536, 37, 30_000, 16);
            var first = BedrockHigh16Search.search(low48, rule, fit, holdout, firstBudget, () -> false);
            check(first.completion() == Full64BiomeCandidateSearch.Completion.EVALUATION_BUDGET
                    && first.evaluations() == 37 && first.nextIndex() == 37,
                    "高16预算结束必须留下准确续跑位置");
            var secondBudget = new Full64BiomeCandidateSearch.SearchBudget(37, 65_536, 65_536, 30_000, 16);
            var second = BedrockHigh16Search.search(low48, rule, fit, holdout, secondBudget, () -> false);
            var combined = new ArrayList<>(first.candidates());
            combined.addAll(second.candidates());
            check(first.evaluations() + second.evaluations() == 65_536
                    && combined.equals(result.candidates()) && !second.completeHigh16Domain(secondBudget),
                    "高16分片合并不得重叠、遗漏或把单分片标成全域");

            var cancelled = BedrockHigh16Search.search(low48, rule, fit, holdout, budget, () -> true);
            check(cancelled.completion() == Full64BiomeCandidateSearch.Completion.CANCELLED
                    && cancelled.evaluations() == 0 && cancelled.nextIndex() == 0,
                    "开始前取消必须零评估且无部分结果");
            var duplicate = new ArrayList<>(holdout);
            duplicate.set(0, fit.getFirst());
            expectIllegal(() -> BedrockHigh16Search.search(low48, rule, fit, duplicate, budget, () -> false));
            var sameChunkHoldout = nativePositives(factory, rule, 0, 32);
            expectIllegal(() -> BedrockHigh16Search.search(low48, rule, fit, sameChunkHoldout,
                    budget, () -> false));
            expectIllegal(() -> BedrockHigh16Search.search(-1, rule, fit, holdout, budget, () -> false));
        }
        check(coveredUnreachableRandomSeed, "必须覆盖不能由Java nextLong产生的人工指定负Seed");
    }

    private static List<PositiveBlock> nativePositives(PositionalRandomFactory factory,
                                                       GradientRule rule, int baseX, int count) {
        var result = new ArrayList<PositiveBlock>();
        // 交替观察两个分离区块，避免样本总数达标而实际全在单一区块。
        for (int i = 0; i < 4096 && result.size() < count; i++) {
            int x = baseX + (i % 2) * 32 + (i / 2) % 16;
            int z = i / 32;
            var point = new PositiveBlock(x, -60, z);
            if (nativeGradient(factory.at(x, -60, z).nextFloat(), rule, -60)) result.add(point);
        }
        check(result.size() == count, "原生随机控制中缺少足量阳性点");
        return List.copyOf(result);
    }

    private static PositiveBlock nativeNegative(PositionalRandomFactory factory,
                                                  GradientRule rule, int baseX) {
        for (int i = 0; i < 1024; i++) {
            int x = baseX + i;
            if (!nativeGradient(factory.at(x, -60, 0).nextFloat(), rule, -60)) {
                return new PositiveBlock(x, -60, 0);
            }
        }
        throw new AssertionError("原生随机控制中缺少阴性条件反例");
    }

    private static RandomSource nativeSource(Algorithm algorithm, long seed) {
        return algorithm == Algorithm.LEGACY
                ? new LegacyRandomSource(seed) : new XoroshiroRandomSource(seed);
    }

    private static void expectIllegal(Runnable action) {
        try {
            action.run();
            throw new AssertionError("无效基岩模型参数应失败关闭");
        } catch (IllegalArgumentException expected) {
            // 非法参数不得被钳制、截断或悄悄换成原版假设。
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
