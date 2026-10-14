package io.github.fxjcangku.worldseedlab.core.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * P3.8 真实地表证据驱动的候选Seed模型排错器。
 *
 * <p>这里的前向生成器是显式注入的研究假设，不能由客户端凭空获得；
 * 即使所有有限候选都匹配，也不代表真实Salt、生成器身份或完整64位已确认。
 * 调用仅在独立离线任务发生，不应放入客户端Tick。
 */
public final class P38TerrainCandidateAudit {
    private P38TerrainCandidateAudit() {}
    private static final int MIN_SEPARATION_CHUNKS = 64; // 保守至少1024方块
    private static final int MAX_SAMPLE_CHUNKS = 64;
    private static final int MAX_CANDIDATE_SEEDS = 64;

    /** 可插拔真实/模拟前向预测接口；实现必须由独立受控世界验证。 */
    public interface ForwardTerrainModel {
        String modelId();
        List<Integer> predictedWorldSurface(long candidateSeed, int chunkX, int chunkZ);
    }

    public enum Verdict {
        /** 在明确的模型假设下，拟合点与留出点都一致；仍不认证生成器身份。 */
        CONDITIONAL_MATCH_UNVERIFIED,
        REJECTED_BY_FIT,
        REJECTED_BY_HOLDOUT,
        INSUFFICIENT_INDEPENDENT_SAMPLES
    }

    public record CandidateResult(long seedBits, Verdict verdict,
                                  int fitChunks, int holdoutChunks,
                                  int fitMismatchCells, int holdoutMismatchCells) {}

    public record Report(String assumedModel, List<CandidateResult> candidates,
                         int distinctRegions, boolean generatorVerified,
                         boolean complete64Recovered) {
        public Report {
            candidates = List.copyOf(candidates);
            if (generatorVerified || complete64Recovered) {
                throw new IllegalArgumentException("显式候选模型审计不能自行认证生成器和完整Seed");
            }
        }
    }

    /** 强制空间独立后固定排序：前2 FIT，剩余HOLDOUT，仅比较真正客户端保存的高度。 */
    public static Report compare(List<TerrainChunkSample> observations,
                                 List<Long> candidateSeedBits,
                                 ForwardTerrainModel predictor) {
        Objects.requireNonNull(observations);
        Objects.requireNonNull(candidateSeedBits);
        Objects.requireNonNull(predictor);
        String modelId = Objects.requireNonNull(predictor.modelId());
        if (modelId.isBlank() || modelId.length() > 120) {
            throw new IllegalArgumentException("缺少明确的前向生成器假设");
        }
        if (observations.size() < 3 || observations.size() > MAX_SAMPLE_CHUNKS) {
            throw new IllegalArgumentException("至少3个、至多64个客户端地形区块才能进行候选审计");
        }
        if (candidateSeedBits.isEmpty() || candidateSeedBits.size() > MAX_CANDIDATE_SEEDS
                || new HashSet<>(candidateSeedBits).size() != candidateSeedBits.size()) {
            throw new IllegalArgumentException("候选Seed必须为1..64个互不重复的显式输入");
        }
        var ordered = observations.stream().sorted(Comparator
                .comparingInt(TerrainChunkSample::chunkX)
                .thenComparingInt(TerrainChunkSample::chunkZ)).toList();
        var first = ordered.getFirst();
        Set<Long> seen = new HashSet<>();
        List<TerrainChunkSample> selected = new ArrayList<>();
        for (var sample : ordered) {
            if (!first.worldFingerprint().equals(sample.worldFingerprint())
                    || !first.dimension().equals(sample.dimension())
                    || !first.clientVersion().equals(sample.clientVersion())
                    || !seen.add(pack(sample.chunkX(), sample.chunkZ()))) {
                throw new IllegalArgumentException("不同世界/维度/版本或重复区块不可当独立地形");
            }
            if (selected.stream().allMatch(previous ->
                    Math.max(Math.abs((long) sample.chunkX() - previous.chunkX()),
                             Math.abs((long) sample.chunkZ() - previous.chunkZ()))
                            >= MIN_SEPARATION_CHUNKS)) {
                selected.add(sample);
            }
        }
        var results = new ArrayList<CandidateResult>();
        if (selected.size() < 3) {
            for (long candidate : candidateSeedBits) {
                results.add(new CandidateResult(candidate, Verdict.INSUFFICIENT_INDEPENDENT_SAMPLES,
                        0, 0, 0, 0));
            }
            return new Report(modelId, results, selected.size(), false, false);
        }
        for (long candidate : candidateSeedBits) {
            int fitBad = 0, holdoutBad = 0;
            for (int index = 0; index < selected.size(); index++) {
                TerrainChunkSample observed = selected.get(index);
                List<Integer> predicted = Objects.requireNonNull(
                        predictor.predictedWorldSurface(candidate,
                                observed.chunkX(), observed.chunkZ()), "null forward prediction");
                if (predicted.size() != 16 || predicted.stream().anyMatch(Objects::isNull)) {
                    throw new IllegalArgumentException("前向生成器必须返回16列完整高度；禁止拼凑缺失值");
                }
                for (int col=0; col<16; col++) {
                    if (!predicted.get(col).equals(observed.surfaceHeights().get(col))) {
                        if (index < 2) fitBad++; else holdoutBad++;
                    }
                }
            }
            Verdict verdict = fitBad > 0 ? Verdict.REJECTED_BY_FIT
                    : holdoutBad > 0 ? Verdict.REJECTED_BY_HOLDOUT
                    : Verdict.CONDITIONAL_MATCH_UNVERIFIED;
            results.add(new CandidateResult(candidate, verdict, 2,
                    selected.size()-2, fitBad, holdoutBad));
        }
        return new Report(modelId, results, selected.size(), false, false);
    }

    private static long pack(int x, int z) {
        return ((long)x << 32) | (z & 0xffffffffL);
    }
}
