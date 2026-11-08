package io.github.fxjcangku.worldseedlab.core.solver;

import io.github.fxjcangku.worldseedlab.core.worldgen.WorldgenVersionAdapter;
import java.util.Objects;
import java.util.function.LongFunction;

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
    private final LongFunction<Full64BiomeCandidateSearch.BiomeSampler> samplerFactory;

    private VanillaOverworldBiomeModel(
            Full64BiomeCandidateSearch.Scope scope,
            LongFunction<Full64BiomeCandidateSearch.BiomeSampler> samplerFactory
    ) {
        this.scope = Objects.requireNonNull(scope);
        this.samplerFactory = Objects.requireNonNull(samplerFactory);
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
        return new VanillaOverworldBiomeModel(scope,
                WorldgenVersionAdapter.createVanillaBiomeFactory(scope.gameVersion()));
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
        return samplerFactory.apply(full64Seed);
    }
}
