package io.github.fxjcangku.worldseedlab.core.solver;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import java.util.Objects;

/**
 * 使用对应版本 Minecraft 官方 Mojmap 的真实 Overworld MultiNoise 气候生成器。
 *
 * <p>本适配器仅能用于明确声明为原版 `minecraft:vanilla_overworld` 的假设模型；
 * 对自定义群系、修改过的噪声设置/数据包/插件生成器，不能声称计算结果与
 * 服务端真实规则相同。普通客户端并不会收到完整生成器配置。
 *
 * <p>一次性解析原版动态注册表，之后每个候选 Seed 新建 RandomState 与
 * Climate.Sampler；这些操作成本较高，必须只在离线研究进程调用。
 * 原始群系观察必须来自真实已加载区块，并经独立留出证据验证。
 */
public final class VanillaOverworldBiomeModel
        implements Full64BiomeCandidateSearch.BiomeModel {
    public static final String GENERATOR_PROFILE = "minecraft:vanilla_overworld";

    private final Full64BiomeCandidateSearch.Scope scope;
    private final HolderGetter<NormalNoise.NoiseParameters> noises;
    private final NoiseGeneratorSettings settings;
    private final MultiNoiseBiomeSource biomeSource;

    private VanillaOverworldBiomeModel(
            Full64BiomeCandidateSearch.Scope scope,
            HolderGetter<NormalNoise.NoiseParameters> noises,
            NoiseGeneratorSettings settings,
            MultiNoiseBiomeSource biomeSource
    ) {
        this.scope = Objects.requireNonNull(scope);
        this.noises = Objects.requireNonNull(noises);
        this.settings = Objects.requireNonNull(settings);
        this.biomeSource = Objects.requireNonNull(biomeSource);
    }

    public static VanillaOverworldBiomeModel create(
            Full64BiomeCandidateSearch.Scope scope
    ) {
        Objects.requireNonNull(scope);
        if (!"minecraft:overworld".equals(scope.dimension())
                || !GENERATOR_PROFILE.equals(scope.generatorProfile())) {
            throw new IllegalArgumentException(
                    "仅支持已确认的原版主世界群系生成器，不支持自定义设置或其它维度");
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var provider = VanillaRegistries.createLookup();
        var noiseLookup = provider.lookupOrThrow(Registries.NOISE);
        var noiseSettings = provider.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var preset = provider.lookupOrThrow(
                        Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        return new VanillaOverworldBiomeModel(scope, noiseLookup, noiseSettings,
                MultiNoiseBiomeSource.createFromPreset(preset));
    }

    @Override
    public Full64BiomeCandidateSearch.Scope scope() {
        return scope;
    }

    @Override
    public Full64BiomeCandidateSearch.ModelTrust trust() {
        // 仅表示模型来自该 Minecraft 版本实际的原版类，
        // **不**表示目标远程服务器已被证明启用了这个生成器。
        return Full64BiomeCandidateSearch.ModelTrust.VERIFIED_TARGET_MINECRAFT_WORLDGEN;
    }

    @Override
    public Full64BiomeCandidateSearch.BiomeSampler prepare(long full64Seed) {
        RandomState state = RandomState.create(settings, noises, full64Seed);
        var climate = state.sampler();
        return (quartX, quartY, quartZ) ->
                biomeSource.getNoiseBiome(quartX, quartY, quartZ, climate)
                        .unwrapKey().orElseThrow().identifier().toString();
    }
}
