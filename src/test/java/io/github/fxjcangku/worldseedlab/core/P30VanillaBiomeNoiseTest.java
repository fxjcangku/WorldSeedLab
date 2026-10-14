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
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private static void verifyIndependentSavedBiomes(
            Path file, Full64BiomeCandidateSearch.Scope scope,
            Full64BiomeCandidateSearch.BiomeModel vanillaModel) {
        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.size() < 9
                    || !lines.getFirst().equals("quart_x,quart_y,quart_z,biome_id")) {
                throw new IllegalArgumentException("真实存档群系 CSV 缺失或独立采样不足");
            }
            Set<String> seen = new HashSet<>();
            List<Full64BiomeCandidateSearch.BiomeSample> real = new ArrayList<>();
            var actualModel = vanillaModel.prepare(2731L);
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) continue;
                String[] row = line.split(",",-1);
                if (row.length != 4) throw new IllegalArgumentException("群系证据列数错误");
                int x = Integer.parseInt(row[0]);
                int y = Integer.parseInt(row[1]);
                int z = Integer.parseInt(row[2]);
                String id = row[3];
                if (!seen.add(x + ":" + y + ":" + z)) {
                    throw new IllegalArgumentException("重复 quart 样本");
                }
                String replayed = actualModel.biomeAtQuart(x,y,z);
                if (!id.equals(replayed)) {
                    throw new AssertionError("实际 Minecraft 已保存群系与原创离线"
                            + "原版模型不一致: quart=" + x + "," + y + "," + z
                            + " saved=" + id + " computed=" + replayed);
                }
                if (real.size() < 12) {
                    real.add(new Full64BiomeCandidateSearch.BiomeSample(
                            scope,x,y,z,id,
                            real.size() < 6 ? Full64BiomeCandidateSearch.Split.FIT
                                    : Full64BiomeCandidateSearch.Split.HOLDOUT,
                            Full64BiomeCandidateSearch.Origin.CONTROL_WORLD_OBSERVED));
                }
            }
            if (real.size() < 8) {
                throw new IllegalArgumentException("受控世界存档中缺少足够的独立群系采样");
            }
            var result = Full64BiomeCandidateSearch.search(
                    List.of(new LocalSmtSeedSolver.VerifiedLow48(2731L,2,1)),
                    real, vanillaModel,
                    new Full64BiomeCandidateSearch.SearchBudget(0,2,2,120_000,8),
                    () -> false);
            if (result.completion()
                        != Full64BiomeCandidateSearch.Completion.REQUESTED_WINDOW_COMPLETE
                    || result.candidates().size() != 1
                    || result.candidates().getFirst().fullSeedBits() != 2731L) {
                throw new AssertionError("受控真实群系样本没有独立排除错误的 high16=1");
            }
            System.out.println("WorldSeedLab P3.0 REAL Minecraft saved quart biome parity "
                    + scope.gameVersion() + ": samples=" + seen.size()
                    + ", FIT=6 HOLDOUT=" + (real.size()-6)
                    + ", low48=2731, full64=2731 survives, high16=1 rejected.");
            System.out.println("限制：仅检查高16位[0,2)的两种候选，"
                    + "受控本机世界存档并非普通客户端远端服务器观测，也不代表扫完全部65536高位。");
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("读取独立存档群系证据失败", error);
        }
    }

    private P30VanillaBiomeNoiseTest() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var provider = VanillaRegistries.createLookup();
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
                RandomState state = RandomState.create(settings, noiseParameters, seed);
                var sampler = state.sampler();
                return (x,y,z) -> source.getNoiseBiome(x,y,z,sampler).unwrapKey()
                        .orElseThrow().identifier().toString();
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
        if (args.length > 1) {
            throw new IllegalArgumentException("最多允许一个受控世界存档群系 CSV");
        }
        if (args.length == 1) {
            verifyIndependentSavedBiomes(Path.of(args[0]), scope, model);
        }
        System.out.println("WorldSeedLab P3.0 real vanilla MultiNoiseBiomeSource target="
                + Long.toHexString(CONTROL) + " sampled quart=" + COORDS.length
                + " highbit differences=" + differences + " candidate count="
                + result.candidates().size()
                + "; MODEL SELF-CHECK ONLY, not independent saved-chunk biome truth.");
    }
}
