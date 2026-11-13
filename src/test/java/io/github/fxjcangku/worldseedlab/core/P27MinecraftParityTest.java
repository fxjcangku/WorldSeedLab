package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadMath;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import io.github.fxjcangku.worldseedlab.core.solver.EffectiveStructureStateSearch;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;

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
        // Minecraft 的结构类型和 Registry Codec 必须在游戏 Bootstrap 之后使用。
        // 这里只初始化离线测试的原版注册表，不连接服务器，也不会加载世界。
        // DataFixers 需要目标 Minecraft 版本元数据，必须先设定再 Bootstrap。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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
            // 真Minecraft自身对照：未知Salt可吸收入有效状态，但不能反向唯一拆分。
            RandomSpreadStructurePlacement zeroSaltMinecraft = new RandomSpreadStructurePlacement(
                    spec.spacing(), spec.separation(), spreadType, 0);
            var zeroSaltSpec = new RandomSpreadSpec(spec.structureSetId(),
                    spec.spacing(), spec.separation(), spec.distribution(), 0);
            for (long seed : new long[] {
                    0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE,
                    12_345L, 0x123456789ABCDEF0L
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
                    long effective = EffectiveStructureStateSearch.effectiveState(seed, spec.salt());
                    var normalizedMinecraft = zeroSaltMinecraft.getPotentialStructureChunk(
                            effective, sourceX, sourceZ);
                    var normalizedOurs = RandomSpreadMath.potentialFromSourceChunk(
                            effective, sourceX, sourceZ, zeroSaltSpec);
                    if (!normalizedMinecraft.equals(trueCandidate)
                            || normalizedOurs.x() != trueCandidate.x()
                            || normalizedOurs.z() != trueCandidate.z()) {
                        throw new AssertionError("真实Minecraft的Seed+Salt有效状态关系不一致");
                    }
                    inspected++;
                }
            }
        }
        System.out.println("WorldSeedLab P2.7 actual Minecraft random-spread parity passed: "
                + inspected + " candidate placements and unknown-Salt E48 equivalences.");
    }
}
