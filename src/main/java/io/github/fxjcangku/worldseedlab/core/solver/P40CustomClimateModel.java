package io.github.fxjcangku.worldseedlab.core.solver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P4.0：用真正 Mojmap RandomState 气候噪声和可审计的六维 MultiNoise 参数列表
 * 预测候选 Seed 的群系。输入参数必须由研究者从独立来源提供；并不会从
 * Terralith 命名空间猜测参数、后台 Salt 或 NoiseRouter。
 *
 * <p>当前只支持目标版本原版 Overworld 气候噪声路由器作为明确假设，
 * 允许自定义气候区间/群系映射。若目标服替换了噪声路由器，匹配或冲突
 * 都不能对真实 Seed 作出结论。此模型始终 UNVERIFIED_OR_CUSTOM。
 */
public final class P40CustomClimateModel implements Full64BiomeCandidateSearch.BiomeModel {
    public static final String SAMPLER = "minecraft:vanilla_overworld_noise_router";
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final int MAX_FILE_SIZE = 1024 * 1024;
    private static final String[] AXES = {
            "temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"
    };

    public record Specification(String generatorProfile, String noiseRouter,
                                List<Pair<Climate.ParameterPoint, String>> entries,
                                Set<String> possibleBiomes) {
        public Specification {
            if (generatorProfile == null || !ID.matcher(generatorProfile).matches()
                    || !generatorProfile.startsWith("worldseedlab:")
                    || !SAMPLER.equals(noiseRouter)
                    || entries == null || entries.isEmpty() || entries.size() > 4096) {
                throw new IllegalArgumentException("缺少独立参数模型或使用了未支持的噪声路由器");
            }
            entries = List.copyOf(entries);
            possibleBiomes = Set.copyOf(possibleBiomes);
        }
    }

    private final Full64BiomeCandidateSearch.Scope scope;
    private final HolderGetter<NormalNoise.NoiseParameters> noises;
    private final NoiseGeneratorSettings settings;
    private final Climate.ParameterList<String> climateBiomes;

    private P40CustomClimateModel(Full64BiomeCandidateSearch.Scope scope,
                                  HolderGetter<NormalNoise.NoiseParameters> noises,
                                  NoiseGeneratorSettings settings,
                                  List<Pair<Climate.ParameterPoint, String>> entries) {
        this.scope = scope;
        this.noises = noises;
        this.settings = settings;
        this.climateBiomes = new Climate.ParameterList<>(entries);
    }

    public static P40CustomClimateModel create(Full64BiomeCandidateSearch.Scope scope,
                                                Specification spec) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(spec);
        if (!"minecraft:overworld".equals(scope.dimension())
                || !scope.generatorProfile().equals(spec.generatorProfile())) {
            throw new IllegalArgumentException("世界维度或生成器配置不匹配");
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registry = VanillaRegistries.createLookup();
        var noises = registry.lookupOrThrow(Registries.NOISE);
        var settings = registry.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        return new P40CustomClimateModel(scope, noises, settings, spec.entries());
    }

    /** 只读取用户本机指定的纯参数 JSON；不执行脚本、不解压、不联网。 */
    public static Specification read(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source) || Files.isSymbolicLink(source)
                || Files.size(source) > MAX_FILE_SIZE) {
            throw new IOException("生成器参数文件不存在、是链接或超过1MiB");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (root.get("schema").getAsInt() != 1
                    || !"USER_SUPPLIED_UNVERIFIED".equals(root.get("provenance").getAsString())) {
                throw new IllegalArgumentException("生成器模型必须显式声明未经服务端验证");
            }
            String id = root.get("generator_profile").getAsString();
            String router = root.get("climate_sampler").getAsString();
            JsonArray rows = root.getAsJsonArray("entries");
            if (rows == null || rows.isEmpty() || rows.size() > 4096) {
                throw new IllegalArgumentException("气候分区数量必须在1..4096之间");
            }
            List<Pair<Climate.ParameterPoint, String>> points = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            for (JsonElement raw : rows) {
                JsonObject row = raw.getAsJsonObject();
                String biome = row.get("biome").getAsString();
                if (biome.length() > 256 || !ID.matcher(biome).matches()) {
                    throw new IllegalArgumentException("气候区包含非法群系ID");
                }
                Climate.Parameter[] axes = new Climate.Parameter[AXES.length];
                for (int i = 0; i < AXES.length; i++) {
                    JsonArray span = row.getAsJsonArray(AXES[i]);
                    if (span == null || span.size() != 2) {
                        throw new IllegalArgumentException("气候坐标必须是[min,max]区间");
                    }
                    float lo = span.get(0).getAsFloat();
                    float hi = span.get(1).getAsFloat();
                    if (!Float.isFinite(lo) || !Float.isFinite(hi)
                            || lo > hi || lo < -4f || hi > 4f) {
                        throw new IllegalArgumentException("气候区间越界或倒置");
                    }
                    axes[i] = Climate.Parameter.span(lo, hi);
                }
                float offset = row.get("offset").getAsFloat();
                if (!Float.isFinite(offset) || offset < 0f || offset > 4f) {
                    throw new IllegalArgumentException("气候偏移量必须在0..4之间");
                }
                points.add(Pair.of(Climate.parameters(axes[0], axes[1], axes[2],
                        axes[3], axes[4], axes[5], offset), biome));
                keys.add(biome);
            }
            return new Specification(id, router, points, keys);
        } catch (RuntimeException bad) {
            throw new IOException("无效或不完整的本地气候参数配置", bad);
        }
    }

    /** 已校准的原版模型仅可用作受控比较，不会提升本模型的可信来源。 */
    public static Specification controlledVanillaPreset(String identifier) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registry = VanillaRegistries.createLookup();
        var preset = registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists.OVERWORLD)
                .value().parameters().values();
        List<Pair<Climate.ParameterPoint, String>> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (var row : preset) {
            String biome = row.getSecond().unwrapKey().orElseThrow().identifier().toString();
            entries.add(Pair.of(row.getFirst(), biome));
            ids.add(biome);
        }
        return new Specification(identifier, SAMPLER, entries, ids);
    }

    @Override
    public Full64BiomeCandidateSearch.Scope scope() { return scope; }

    @Override
    public Full64BiomeCandidateSearch.ModelTrust trust() {
        return Full64BiomeCandidateSearch.ModelTrust.UNVERIFIED_OR_CUSTOM;
    }

    @Override
    public Full64BiomeCandidateSearch.BiomeSampler prepare(long full64Seed) {
        var climate = RandomState.create(settings, noises, full64Seed).sampler();
        return (x, y, z) -> climateBiomes.findValue(climate.sample(x, y, z));
    }
}
