package io.github.fxjcangku.worldseedlab.core.solver;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * P4.3 原创「气候向量→可见自定义群系标签」可泛化性对照。
 *
 * <p>与从Terralith读取世界生成规则不同：仅对显式完整64位假设运行对应
 * Minecraft Mojmap 原版 Overworld RandomState.sampler()，获得六维气候。
 * 在不同地域FIT样本上学习无参数最近邻映射，再只在另两个HOLDOUT
 * 宏区域测试是否真的能预测从未参与训练的客户端自定义群系标签。
 *
 * <p>错误Seed/错NoiseRouter可能也得到类似分数；任何正结果都只能叫
 * 条件兼容性线索，不得称为后台世界生成器身份、世界Seed或全域穷举。
 */
public final class P43ClimateFingerprintAudit {
    private P43ClimateFingerprintAudit() {}

    public enum Verdict {
        EXPLORATORY_ONLY, INSUFFICIENT_SPATIALLY_INDEPENDENT_SAMPLES,
        INSUFFICIENT_LABEL_DIVERSITY
    }

    public record Score(long seedBits, int holdoutRows, int correctlyPredicted,
                        int baselineCorrect, int unseenHoldoutLabels,
                        double accuracy, double baselineAccuracy,
                        double minHoldoutRegionAccuracy) {
        public String signedSeed() { return Long.toString(seedBits); }
        public String hexSeed() { return "%016x".formatted(seedBits); }
    }

    public record Outcome(Verdict verdict, int fitRows, int holdoutRows,
                          int fitRegions, int holdoutRegions, int fitLabelKinds,
                          List<Score> scores) {
        public Outcome { scores = List.copyOf(scores); }
    }

    private record Vector(String biome, String region, double[] axes) {
        private Vector { axes = axes.clone(); }
    }

    /**
     * 可直接由实际Minecraft原生RandomState计算验证，不使用自定义模型配置。
     * 六轴的相对尺度是Minecraft 1e4气候定点数，距离仅用于对比，无Seed真值。
     */
    public static double[] climateAxes(Climate.TargetPoint point) {
        Objects.requireNonNull(point);
        return new double[] {
                point.temperature() / 10000.0,
                point.humidity() / 10000.0,
                point.continentalness() / 10000.0,
                point.erosion() / 10000.0,
                point.depth() / 10000.0,
                point.weirdness() / 10000.0
        };
    }

    private static double distanceSquared(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < 6; i++) {
            double v = a[i] - b[i];
            sum += v * v;
        }
        return sum;
    }

    /** 1近邻分类器；没有拟合自定义区间或读取任何目标服NoiseRouter。 */
    private static String nearestLabel(List<Vector> fit, double[] target) {
        double best = Double.POSITIVE_INFINITY;
        String label = null;
        for (var candidate : fit) {
            double distance = distanceSquared(candidate.axes(), target);
            if (distance < best
                    || (distance == best
                        && (label == null || candidate.biome().compareTo(label) < 0))) {
                best = distance;
                label = candidate.biome();
            }
        }
        return Objects.requireNonNull(label);
    }

    private static String majorityLabel(List<P43SpatialClimateEvidence.Point> fits) {
        Map<String,Integer> counts = new HashMap<>();
        fits.forEach(p -> counts.merge(p.biomeId(), 1, Integer::sum));
        return counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String,Integer>>comparingInt(
                        e -> e.getValue()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .findFirst().orElseThrow().getKey();
    }

    public static Outcome evaluate(P43SpatialClimateEvidence.Archive archive,
                                   List<Long> explicitSeeds, int expectedMaxSeedCount) {
        Objects.requireNonNull(archive);
        Objects.requireNonNull(explicitSeeds);
        if (expectedMaxSeedCount < 1 || expectedMaxSeedCount > 8
                || explicitSeeds.isEmpty() || explicitSeeds.size() > expectedMaxSeedCount
                || new HashSet<>(explicitSeeds).size() != explicitSeeds.size()) {
            throw new IllegalArgumentException("仅支持1..8个不重复显式完整64位候选假设");
        }
        if (!archive.scope().gameVersion().equals(System.getProperty("mcVersion", "26.2"))) {
            throw new IllegalArgumentException("离线Minecraft实际版本与客户端证据不一致");
        }
        var fits = archive.points().stream()
                .filter(p -> p.split() == P43SpatialClimateEvidence.Split.FIT).toList();
        var holdouts = archive.points().stream()
                .filter(p -> p.split() == P43SpatialClimateEvidence.Split.HOLDOUT).toList();
        var knownFitLabels = new HashSet<String>();
        fits.forEach(p -> knownFitLabels.add(p.biomeId()));
        if (fits.size() < 24 || holdouts.size() < 16
                || archive.fitRegions() < 3 || archive.holdoutRegions() < 2) {
            return new Outcome(Verdict.INSUFFICIENT_SPATIALLY_INDEPENDENT_SAMPLES,
                    fits.size(), holdouts.size(), archive.fitRegions(),
                    archive.holdoutRegions(), knownFitLabels.size(), List.of());
        }
        if (knownFitLabels.size() < 2) {
            return new Outcome(Verdict.INSUFFICIENT_LABEL_DIVERSITY,
                    fits.size(), holdouts.size(), archive.fitRegions(),
                    archive.holdoutRegions(), knownFitLabels.size(), List.of());
        }

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var provider = VanillaRegistries.createLookup();
        HolderGetter<NormalNoise.NoiseParameters> noises = provider.lookupOrThrow(Registries.NOISE);
        var settings = provider.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        String majority = majorityLabel(fits);
        int baselineCorrect = (int) holdouts.stream()
                .filter(p -> p.biomeId().equals(majority)).count();
        int unseen = (int) holdouts.stream()
                .filter(p -> !knownFitLabels.contains(p.biomeId())).count();
        var scores = new ArrayList<Score>();
        for (long seed : explicitSeeds) {
            var sampler = RandomState.create(settings, noises, seed).sampler();
            List<Vector> trained = new ArrayList<>();
            for (var point : fits) {
                trained.add(new Vector(point.biomeId(), point.region(),
                        climateAxes(sampler.sample(point.quartX(),
                                point.quartY(), point.quartZ()))));
            }
            int accurate = 0;
            Map<String,int[]> byRegion = new HashMap<>();
            for (var point : holdouts) {
                String predicted = nearestLabel(trained, climateAxes(sampler.sample(
                        point.quartX(), point.quartY(), point.quartZ())));
                boolean same = point.biomeId().equals(predicted);
                if (same) accurate++;
                int[] region = byRegion.computeIfAbsent(point.region(), key -> new int[2]);
                region[0]++;
                if (same) region[1]++;
            }
            double minimum = byRegion.values().stream().mapToDouble(
                    counts -> counts[1] / (double) counts[0]).min().orElse(0);
            scores.add(new Score(seed, holdouts.size(), accurate, baselineCorrect,
                    unseen, accurate / (double) holdouts.size(),
                    baselineCorrect / (double) holdouts.size(), minimum));
        }
        return new Outcome(Verdict.EXPLORATORY_ONLY,
                fits.size(), holdouts.size(), archive.fitRegions(),
                archive.holdoutRegions(), knownFitLabels.size(), scores);
    }
}
