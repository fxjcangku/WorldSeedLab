package io.github.fxjcangku.worldseedlab.core.solver;

/**
 * Java 48 位线性同余随机源的独立数学实现。
 *
 * <p>只在确认使用 legacy 随机源的结构放置模型中调用。
 * 内部只保存 48 位状态；结果不能用于推断完整的高 16 位世界种子。
 */
public final class LegacyLcg48 {
    public static final long MASK = (1L << 48) - 1;
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private long state;

    public LegacyLcg48(long externalSeed) {
        state = (externalSeed ^ MULTIPLIER) & MASK;
    }

    /** 只供可复现的已知 Seed 测试使用的位流步进。 */
    public int nextBits(int bits) {
        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException("bits out of range");
        }
        state = (state * MULTIPLIER + ADDEND) & MASK;
        return (int) (state >>> (48 - bits));
    }

    /**
     * 与 Java 受界整数算法一致：二的幂使用乘法，其余使用拒绝采样。
     * 不得简单执行 nextBits(31) % bound：非二次幂会产生偏差与错位。
     */
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        int bits = nextBits(31);
        int mask = bound - 1;
        if ((bound & mask) == 0) {
            return (int) ((bound * (long) bits) >> 31);
        }
        int result = bits % bound;
        // Java int 有符号溢出参与拒绝条件；保持 int 运算语义。
        while (bits - result + mask < 0) {
            bits = nextBits(31);
            result = bits % bound;
        }
        return result;
    }

    public long state48() {
        return state;
    }
}
