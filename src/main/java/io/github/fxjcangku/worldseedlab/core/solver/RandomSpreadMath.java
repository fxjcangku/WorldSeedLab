package io.github.fxjcangku.worldseedlab.core.solver;

import java.util.Objects;

/**
 * 原创 random_spread 候选区块前向数学模型。
 *
 * <p>此计算的前提是目标结构集合确实使用 Minecraft legacy
 * random_spread 机制及给定参数。它不运行群系过滤、结构排除、频率
 * 缩减或模板拼装，所以只能得出“可能的放置区块”，不是已生成建筑。
 */
public final class RandomSpreadMath {
    private static final long X_MULTIPLIER = 341873128712L;
    private static final long Z_MULTIPLIER = 132897987541L;

    private RandomSpreadMath() {
    }

    public record Chunk(int x, int z) {
        public int regionX(RandomSpreadSpec spec) {
            return Math.floorDiv(x, spec.spacing());
        }

        public int regionZ(RandomSpreadSpec spec) {
            return Math.floorDiv(z, spec.spacing());
        }
    }

    /** 按给定 region 推导唯一一个潜在结构放置区块。 */
    public static Chunk potentialChunk(long worldSeed, int regionX, int regionZ,
                                       RandomSpreadSpec spec) {
        Objects.requireNonNull(spec);
        long mixed = worldSeed
                + X_MULTIPLIER * regionX
                + Z_MULTIPLIER * regionZ
                + spec.salt();
        LegacyLcg48 random = new LegacyLcg48(mixed);
        int dx = spec.evaluateOffset(random);
        int dz = spec.evaluateOffset(random);
        return new Chunk(
                Math.addExact(Math.multiplyExact(regionX, spec.spacing()), dx),
                Math.addExact(Math.multiplyExact(regionZ, spec.spacing()), dz)
        );
    }

    /**
     * sourceX/Z 是区块坐标，不是 region 索引，更不是方块坐标。
     * 负区块使用 floorDiv；直接 / 会把负半轴结构推到错误 Region。
     */
    public static Chunk potentialFromSourceChunk(long worldSeed, int sourceX, int sourceZ,
                                                  RandomSpreadSpec spec) {
        return potentialChunk(worldSeed, Math.floorDiv(sourceX, spec.spacing()),
                Math.floorDiv(sourceZ, spec.spacing()), spec);
    }

    /** 当前 legacy 模型只读取世界种子的低 48 位，不输出完整世界 Seed。 */
    public static long low48(long seed) {
        return seed & LegacyLcg48.MASK;
    }

    public static String low48Hex(long seed) {
        return "%012x".formatted(low48(seed));
    }
}
