package io.github.fxjcangku.worldseedlab.core.solver;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/**
 * 两版 Mojmap 基岩 vertical_gradient 的原创随机分支模型，不是完整方块生成器。
 *
 * <p>世界 Seed 经 RandomState 的两次命名位置派生后，每个 XYZ 独立取得第一个
 * nextFloat；该分支不使用结构 Salt。原版主世界使用 Xoroshiro，而原版下界使用
 * Legacy，后者在第一次初始化时便丢失高 16 位，不能单独恢复完整世界 Seed。
 *
 * <p>真实 SurfaceSystem 只在生成期默认岩石处执行规则，空气、流体、后续改造、
 * 自定义规则顺序和旧区块都会改变最终方块。因此这里仅接受“已观察到基岩”的
 * 单向条件，不提供“非基岩即随机条件为假”的入口。规则名、绝对生成高度、
 * 随机算法和天然来源均须由外层明确记录假设；数学匹配不认证服务器生成器。
 */
public final class BedrockRandomModel {
    public static final int FLOAT_DOMAIN = 1 << 24;
    private static final long GOLDEN_RATIO = 0x9E3779B97F4A7C15L;
    private static final long SILVER_RATIO = 0x6A09E667F3BCC909L;
    private static final long LEGACY_INVERSE = BigInteger.valueOf(LegacyLcg48.MULTIPLIER)
            .modInverse(BigInteger.ONE.shiftLeft(48)).longValueExact();
    private static final Pattern RULE_NAME = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private BedrockRandomModel() {
    }

    /** 必须显式选择，不能仅凭维度名称推断未知服务端的随机算法。 */
    public enum Algorithm { LEGACY, XOROSHIRO }

    /**
     * 已解析为绝对 Y 的梯度；inverted 对应原版下界顶部的 not(vertical_gradient)。
     *
     * <p>above_bottom/below_top 使用的是 WorldGenerationContext 的生成范围，
     * 并不一定等于客户端可建造范围；此处禁止自动替调用者猜测锚点。
     */
    public record GradientRule(String randomName, int trueAtAndBelow,
                               int falseAtAndAbove, boolean inverted) {
        public GradientRule {
            requireRuleName(randomName);
            if (trueAtAndBelow >= falseAtAndAbove) {
                throw new IllegalArgumentException("基岩梯度下界必须小于上界");
            }
        }
    }

    /** 已由上游确认方块 ID 是基岩的坐标；本记录本身不认证其天然生成来源。 */
    public record PositiveBlock(int x, int y, int z) {
    }

    /**
     * 精确取得命名位置随机源 nextFloat 的 24 位分子，范围 0..2^24-1。
     *
     * <p>不使用浮点近似推导种子；原生 float 等于返回值乘以精确的 2^-24。
     * 每个坐标重置独立随机源，不能把多个位置错误接成连续随机数序列。
     */
    public static int nextFloat24(long worldSeed, Algorithm algorithm, String randomName,
                                  int x, int y, int z) {
        Objects.requireNonNull(algorithm, "随机算法不能为空");
        requireRuleName(randomName);
        if (algorithm == Algorithm.LEGACY) {
            return legacyNextFloat24(legacyNamedState(worldSeed, randomName), x, y, z);
        }
        return xoroshiroFloat24(xoroshiroNamedFactory(worldSeed, randomName), x, y, z);
    }

    /** 只判断一个阳性基岩点是否满足声明的随机分支，不预测最终世界方块。 */
    public static boolean predictsBedrock(long worldSeed, Algorithm algorithm,
                                          GradientRule rule, PositiveBlock block) {
        Objects.requireNonNull(algorithm, "随机算法不能为空");
        Objects.requireNonNull(rule, "基岩规则不能为空");
        Objects.requireNonNull(block, "阳性基岩点不能为空");
        int threshold = threshold24(rule, block.y());
        if (threshold == 0 || threshold == FLOAT_DOMAIN) {
            return (threshold == FLOAT_DOMAIN) != rule.inverted();
        }
        int value = nextFloat24(worldSeed, algorithm, rule.randomName(),
                block.x(), block.y(), block.z());
        return (value < threshold) != rule.inverted();
    }

    /**
     * 复核同一规则的非空阳性列表；只派生一次命名工厂，不为每点分配随机源。
     * 证据作用域、FIT/HOLDOUT 和独立性由外层审计，不能由本方法的 true 代替。
     */
    public static boolean matchesPositive(long worldSeed, Algorithm algorithm,
                                          GradientRule rule, List<PositiveBlock> blocks) {
        Objects.requireNonNull(algorithm, "随机算法不能为空");
        Objects.requireNonNull(rule, "基岩规则不能为空");
        Objects.requireNonNull(blocks, "阳性基岩列表不能为空");
        if (blocks.isEmpty()) throw new IllegalArgumentException("缺少阳性基岩证据");
        long legacyState = algorithm == Algorithm.LEGACY
                ? legacyNamedState(worldSeed, rule.randomName()) : 0L;
        Pair xoroshiro = algorithm == Algorithm.XOROSHIRO
                ? xoroshiroNamedFactory(worldSeed, rule.randomName()) : null;
        for (PositiveBlock block : blocks) {
            Objects.requireNonNull(block, "阳性基岩点不能为空");
            checkCancelled();
            int threshold = threshold24(rule, block.y());
            boolean gradient;
            if (threshold == 0 || threshold == FLOAT_DOMAIN) {
                gradient = threshold == FLOAT_DOMAIN;
            } else {
                int value = algorithm == Algorithm.LEGACY
                        ? legacyNextFloat24(legacyState, block.x(), block.y(), block.z())
                        : xoroshiroFloat24(xoroshiro, block.x(), block.y(), block.z());
                gradient = value < threshold;
            }
            if (gradient == rule.inverted()) return false;
        }
        return true;
    }

    /**
     * 原始梯度成立的整数半开阈值：nextFloat24 &lt; threshold24。
     *
     * <p>原生 compute 使用 double 版 Mth.map，再把 nextFloat 提升为 double 比较。
     * 显式按这条运算顺序计算概率，并以原生 float 乘法作二分边界，避免误用
     * Mth.map(float,...)、整数百分比或 floor 导致边界相差一格。反转由调用方处理。
     */
    public static int threshold24(GradientRule rule, int y) {
        Objects.requireNonNull(rule, "基岩规则不能为空");
        if (y <= rule.trueAtAndBelow()) return FLOAT_DOMAIN;
        if (y >= rule.falseAtAndAbove()) return 0;
        double progress = ((double) y - (double) rule.trueAtAndBelow())
                / ((double) rule.falseAtAndAbove() - (double) rule.trueAtAndBelow());
        double probability = 1.0D + progress * (0.0D - 1.0D);
        int low = 0;
        int high = FLOAT_DOMAIN;
        while (low < high) {
            int middle = (low + high) >>> 1;
            double nativeFloat = (float) middle * 0x1.0p-24F;
            if (nativeFloat < probability) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    /**
     * Mojmap Mth.getSeed 的坐标散列。X 的第一次乘法必须先按 int 溢出，
     * 然后才符号扩展；最后是 long 算术右移，不能改成无符号右移。
     */
    public static long coordinateSeed(int x, int y, int z) {
        long mixed = (long) (x * 3_129_871) ^ (long) z * 116_129_781L ^ (long) y;
        return (mixed * mixed * 42_317_861L + mixed * 11L) >> 16;
    }

    /**
     * 返回第二次 forkPositional 的工厂外部种子低 48 位，不是世界 low48，
     * 也不是 LCG 内部状态。命名派生使用 Java String.hashCode 的有符号 int。
     */
    public static long legacyNamedState(long worldSeed, String randomName) {
        requireRuleName(randomName);
        long rootFactorySeed = legacyNextLong(new LegacyLcg48(worldSeed));
        long namedExternalSeed = rootFactorySeed ^ (long) randomName.hashCode();
        return legacyNextLong(new LegacyLcg48(namedExternalSeed)) & LegacyLcg48.MASK;
    }

    /**
     * 已求出的命名工厂低 48 位到位置 nextFloat；每点恰好一次 Legacy LCG。
     * 此入口供离线 SMT 候选回验，避免反复派生命名工厂和分配随机源对象。
     */
    public static int legacyNextFloat24(long namedStateLow48, int x, int y, int z) {
        requireLow48(namedStateLow48);
        long internal = (namedStateLow48 ^ coordinateSeed(x, y, z)
                ^ LegacyLcg48.MULTIPLIER) & LegacyLcg48.MASK;
        return (int) (legacyStep(internal) >>> 24);
    }

    /**
     * 逆一个 Legacy nextLong 的低 48 位，返回调用前的全部 LCG 内部状态。
     *
     * <p>nextLong 的第二个 next(32) 是有符号 int，以 long 加法组合；它为负时
     * 会向高 32 位借位，不能按无符号拼接。输出低 32 位给出第二状态的高 32 位；
     * 穷尽其低 16 位共 65,536 种，逆两步并复算输出，所以没有截断前像或假设唯一。
     * 中途取消抛异常，绝不把部分列表标成完成。
     */
    public static List<Long> reverseLegacyNextLongLow48(long outputLow48) {
        requireLow48(outputLow48);
        List<Long> beforeNextLongStates = new ArrayList<>();
        long secondHigh32 = (outputLow48 & 0xFFFF_FFFFL) << 16;
        for (int low16 = 0; low16 < 65_536; low16++) {
            if ((low16 & 1023) == 0) checkCancelled();
            long secondInternal = secondHigh32 | low16;
            long firstInternal = legacyPrevious(secondInternal);
            long combined = ((long) (int) (firstInternal >>> 16) << 32)
                    + (long) (int) (secondInternal >>> 16);
            if ((combined & LegacyLcg48.MASK) == outputLow48) {
                beforeNextLongStates.add(legacyPrevious(firstInternal));
            }
        }
        beforeNextLongStates.sort(Long::compare);
        return List.copyOf(beforeNextLongStates);
    }

    /**
     * 从命名工厂低 48 位撤销名称和两层 nextLong，保留全部世界 low48 前像。
     *
     * <p>先逆命名工厂得到第二次 Legacy 初始化后的内部状态，再撤销初始化异或
     * 和名称散列，取得根工厂 nextLong 低 48 位，最后再逆一层。返回值按无符号
     * 48 位范围升序去重并完整前向回验；永不补写未知高 16 位或输出“完整 Seed”。
     */
    public static List<Long> recoverLegacyWorldLow48(long namedStateLow48, String randomName) {
        requireLow48(namedStateLow48);
        requireRuleName(randomName);
        TreeSet<Long> worldLow48Candidates = new TreeSet<>();
        for (long namedInitialInternal : reverseLegacyNextLongLow48(namedStateLow48)) {
            long rootOutputLow48 = (namedInitialInternal ^ LegacyLcg48.MULTIPLIER
                    ^ (long) randomName.hashCode()) & LegacyLcg48.MASK;
            for (long rootInitialInternal : reverseLegacyNextLongLow48(rootOutputLow48)) {
                long candidate = rootInitialInternal ^ LegacyLcg48.MULTIPLIER;
                if (legacyNamedState(candidate, randomName) != namedStateLow48) {
                    throw new IllegalStateException("基岩 Legacy 逆向前像未通过独立前向复算");
                }
                worldLow48Candidates.add(candidate);
            }
        }
        return List.copyOf(worldLow48Candidates);
    }

    private static long legacyNextLong(LegacyLcg48 random) {
        return ((long) random.nextBits(32) << 32) + (long) random.nextBits(32);
    }

    private static long legacyStep(long internal) {
        return (internal * LegacyLcg48.MULTIPLIER + LegacyLcg48.ADDEND) & LegacyLcg48.MASK;
    }

    private static long legacyPrevious(long internal) {
        return ((internal - LegacyLcg48.ADDEND) * LEGACY_INVERSE) & LegacyLcg48.MASK;
    }

    private static Pair xoroshiroNamedFactory(long seed, String name) {
        long unmixed = seed ^ SILVER_RATIO;
        Pair root = forkPositional(new Pair(mixStafford13(unmixed),
                mixStafford13(unmixed + GOLDEN_RATIO)));
        Pair hash = nameHash(name);
        return forkPositional(new Pair(root.low() ^ hash.low(), root.high() ^ hash.high()));
    }

    private static int xoroshiroFloat24(Pair factory, int x, int y, int z) {
        long low = factory.low() ^ coordinateSeed(x, y, z);
        long high = factory.high();
        // 原生 Xoroshiro128PlusPlus 构造器不允许全零内部状态。
        if ((low | high) == 0) {
            low = GOLDEN_RATIO;
            high = SILVER_RATIO;
        }
        return (int) ((Long.rotateLeft(low + high, 17) + low) >>> 40);
    }

    private static Pair forkPositional(Pair pair) {
        long low = pair.low();
        long high = pair.high();
        if ((low | high) == 0) {
            low = GOLDEN_RATIO;
            high = SILVER_RATIO;
        }
        long first = Long.rotateLeft(low + high, 17) + low;
        long xor = high ^ low;
        long nextLow = Long.rotateLeft(low, 49) ^ xor ^ (xor << 21);
        long nextHigh = Long.rotateLeft(xor, 28);
        long second = Long.rotateLeft(nextLow + nextHigh, 17) + nextLow;
        return new Pair(first, second);
    }

    private static long mixStafford13(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static Pair nameHash(String name) {
        try {
            // 官方 seedFromHashOf 按 UTF-8 的 MD5 两个大端 long 使用，
            // 这里 MD5 仅复现命名随机派生，不参与身份认证或安全散列。
            byte[] bytes = MessageDigest.getInstance("MD5")
                    .digest(name.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            return new Pair(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("当前 Java 缺少基岩命名随机派生所需的 MD5", failure);
        }
    }

    private static void requireRuleName(String randomName) {
        if (randomName == null || randomName.length() > 256
                || !RULE_NAME.matcher(randomName).matches()) {
            throw new IllegalArgumentException("必须显式提供合法的完整命名空间基岩随机规则名");
        }
    }

    private static void requireLow48(long value) {
        if (value < 0 || value > LegacyLcg48.MASK) {
            throw new IllegalArgumentException("命名工厂或随机输出必须是无符号 48 位值");
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("基岩随机研究已取消，未完成结果不得继续使用");
        }
    }

    private record Pair(long low, long high) {
    }
}
