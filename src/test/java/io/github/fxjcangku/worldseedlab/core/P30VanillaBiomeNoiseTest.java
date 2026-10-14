package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.LocalSmtSeedSolver;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import java.util.ArrayList;
import java.util.List;

/**
 * 使用目标 Minecraft 自己的完整 Overworld 气候噪声与参数列表，
 * 检查64位世界生成器的高16位可辨识性以及正确的 quart 坐标。
 *
 * <p>该实验的观测数据由同一受控生成器创建，尚不是已加载区块的独立
 * 存档观测，因此证明模型可执行，但不能称为真实未知服务器64位破解。
 */
public final class P30VanillaBiomeNoiseTest {
    private static final long CONTROL = 0xBEEF000000000AABL;
    private static final int[][] COORDS = {
            {3007, 18, -780}, {-4500, 8, 1904}, {9200, -17, -1100},
            {1010, 41, 5700}, {-7001, 29, 9012}, {2400, 66, 4400}
    };

    private P30VanillaBiomeNoiseTest() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var provider = VanillaRegistries.createWorldLookup();
        var noiseParameters = provider.lookupOrThrow(Registries.NOISE);
        var settings = provider.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var parameters = provider.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromPreset(parameters);

        Full64BiomeCandidateSearch.Scope scope = new Full64BiomeCandidateSearch.Scope(
                "2".repeat(64), System.getProperty("mcVersion", "26.2"),
                "minecraft:overworld", "minecraft:vanilla_overworld");
        Full64BiomeCandidateSearch.BiomeModel model = new Full64BiomeCandidateSearch.BiomeModel() {
            public Full64BiomeCandidateSearch.Scope scope() { return scope; }
            public Full64BiomeCandidateSearch.ModelTrust trust() {
                return Full64BiomeCandidateSearch.ModelTrust.CONTROLLED_EXPERIMENT_ONLY;
            }
            public Full64BiomeCandidateSearch.BiomeSampler prepare(long seed) {
                RandomState state = RandomState.create(noiseParameters, seed, settings);
                var sampler = state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
                return (x,y,z) -> source.getNoiseBiome(x,y,z,sampler).unwrapKey()
                        .orElseThrow().location().toString();
            }
        };
        var truth = model.prepare(CONTROL);
        var another = model.prepare(CONTROL ^ (1L << 48));
        int differences = 0;
        List<Full64BiomeCandidateSearch.BiomeSample> observed = new ArrayList<>();
        for (int i=0;i<COORDS.length;i++) {
            int[] p = COORDS[i];
            String biome = truth.biomeAtQuart(p[0],p[1],p[2]);
            if (!biome.equals(another.biomeAtQuart(p[0],p[1],p[2]))) differences++;
            observed.add(new Full64BiomeCandidateSearch.BiomeSample(
                    scope,p[0],p[1],p[2],biome,
                    i<4 ? Full64BiomeCandidateSearch.Split.FIT :
                            Full64BiomeCandidateSearch.Split.HOLDOUT,
                    Full64BiomeCandidateSearch.Origin.CONTROL_WORLD_OBSERVED));
        }
        if (differences < 1) {
            throw new AssertionError("real vanilla climate/biome generator must expose high16 sensitivity");
        }
        long hi= (CONTROL >>> 48) & 0xFFFF;
        // 只查一个极窄窗口，真实噪声初始化成本远高于受控 RNG；全高16性能待独立基准。
        var result = Full64BiomeCandidateSearch.search(
                List.of(new LocalSmtSeedSolver.VerifiedLow48(CONTROL & ((1L<<48)-1),2,1)),
                observed, model,
                new Full64BiomeCandidateSearch.SearchBudget(hi-2,hi+3,5,120_000,8),
                () -> false);
        if (result.candidates().stream().noneMatch(candidate ->
                candidate.fullSeedBits() == CONTROL && candidate.holdoutMatches() == 2)) {
            throw new AssertionError("actual Vanilla Overworld biome model must retain exact control seed");
        }
        System.out.println("WorldSeedLab P3.0 real vanilla MultiNoiseBiomeSource target="
                + Long.toHexString(CONTROL) + " sampled quart=" + COORDS.length
                + " highbit differences=" + differences + " candidate count="
                + result.candidates().size()
                + "; MODEL SELF-CHECK ONLY, not independent saved-chunk biome truth.");
    }
}
