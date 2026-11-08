package io.github.fxjcangku.worldseedlab.core.worldgen;

import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import java.util.function.LongFunction;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Minecraft 26.1.2 的真实Mojmap世界生成桥接，只隔离版本API差异。
 *
 * <p>原版噪声和参数表仍由该版本游戏自身提供；不从旧版本复制生成结果。
 * 运行时版本必须与证据及编译目标相符，不能把同一JAR的计算冒充其它版本真值。
 */
public final class WorldgenVersionAdapter {
    private static final String TARGET_VERSION = "26.1.2";

    private WorldgenVersionAdapter() {}

    public static HolderLookup.Provider createWorldLookup() {
        return createWorldLookup(TARGET_VERSION);
    }

    public static HolderLookup.Provider createWorldLookup(String expectedVersion) {
        SharedConstants.tryDetectVersion();
        if (!TARGET_VERSION.equals(expectedVersion)
                || !TARGET_VERSION.equals(SharedConstants.getCurrentVersion().id())) {
            throw new IllegalArgumentException("世界生成模型的证据版本、编译目标和运行时版本必须一致");
        }
        Bootstrap.bootStrap();
        return VanillaRegistries.createLookup();
    }

    /** 一份注册表上下文供多候选使用；每个完整Seed仍单独创建RandomState。 */
    public static LongFunction<Climate.Sampler> createClimateFactory(String expectedVersion) {
        return climateFactory(createWorldLookup(expectedVersion));
    }

    public static LongFunction<Full64BiomeCandidateSearch.BiomeSampler> createVanillaBiomeFactory(
            String expectedVersion) {
        var registries = createWorldLookup(expectedVersion);
        var climates = climateFactory(registries);
        var preset = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        var source = MultiNoiseBiomeSource.createFromPreset(preset);
        return seed -> {
            var climate = climates.apply(seed);
            return (quartX, quartY, quartZ) -> source
                    .getNoiseBiome(quartX, quartY, quartZ, climate)
                    .unwrapKey().orElseThrow().identifier().toString();
        };
    }

    private static LongFunction<Climate.Sampler> climateFactory(HolderLookup.Provider registries) {
        var noises = registries.lookupOrThrow(Registries.NOISE);
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        return seed -> RandomState.create(settings, noises, seed).sampler();
    }
}
