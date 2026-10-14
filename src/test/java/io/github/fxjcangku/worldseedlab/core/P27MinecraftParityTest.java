package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadMath;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;

/**
 * 直接使用 CI 对应版本 Minecraft 本体作规则预言机，不依赖其它破解工具。
 *
 * <p>仅验证 random_spread 候选区块计算，不验证结构是否最终成功生成；
 * 自定义数据包频率、排除区与群系过滤不属于本轮算法。
 */
public final class P27MinecraftParityTest {
    private P27MinecraftParityTest() {
    }

    public static void main(String[] args) {
        int inspected = 0;
        for (RandomSpreadSpec spec : new RandomSpreadSpec[] {
                new RandomSpreadSpec("minecraft:research_linear", 32, 8,
                        RandomSpreadSpec.Distribution.LINEAR, 10387313),
                new RandomSpreadSpec("minecraft:research_triangular", 24, 6,
                        RandomSpreadSpec.Distribution.TRIANGULAR, 14357617),
                new RandomSpreadSpec("minecraft:research_dense", 7, 2,
                        RandomSpreadSpec.Distribution.LINEAR, -17777)
        }) {
            RandomSpreadType spreadType =
                    spec.distribution() == RandomSpreadSpec.Distribution.LINEAR
                            ? RandomSpreadType.LINEAR
                            : RandomSpreadType.TRIANGULAR;
            RandomSpreadStructurePlacement minecraft = new RandomSpreadStructurePlacement(
                    spec.spacing(), spec.separation(), spreadType, spec.salt());
            for (long seed : new long[] {
                    0L, 1L, -1L, Long.MIN_VALUE, 12_345L, 0x123456789ABCDEF0L
            }) {
                for (int[] region : new int[][] {
                        {-101, -99}, {-19, 13}, {-1, -1}, {-1, 0}, {0, -1},
                        {0, 0}, {1, 1}, {5, -7}, {201, 319}
                }) {
                    int sourceX = Math.multiplyExact(region[0], spec.spacing());
                    int sourceZ = Math.multiplyExact(region[1], spec.spacing());
                    var trueCandidate = minecraft.getPotentialStructureChunk(
                            seed, sourceX, sourceZ);
                    var originalCandidate = RandomSpreadMath.potentialFromSourceChunk(
                            seed, sourceX, sourceZ, spec);
                    if (trueCandidate.x() != originalCandidate.x()
                            || trueCandidate.z() != originalCandidate.z()) {
                        throw new AssertionError("Original 48bit RNG mismatch: spec=" + spec
                                + " seed=" + seed + " region="
                                + region[0] + "," + region[1]
                                + " vanilla=" + trueCandidate
                                + " ours=" + originalCandidate);
                    }
                    inspected++;
                }
            }
        }
        System.out.println("WorldSeedLab P2.7 actual Minecraft random-spread parity passed: "
                + inspected + " candidate placements.");
    }
}
