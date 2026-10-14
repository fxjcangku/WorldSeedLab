package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.generation.P38TerrainCandidateAudit;
import io.github.fxjcangku.worldseedlab.core.generation.TerrainChunkSample;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 离线受控模型正反例，不代表已反推出未知自定义地形服务器的Seed。 */
public final class P38TerrainCandidateSmokeTest {
    private P38TerrainCandidateSmokeTest() {}
    private static final String HASH = "a".repeat(64);

    public static void main(String[] args) {
        var observations = List.of(sample(0, "26.2", HASH),
                sample(128, "26.2", HASH), sample(256, "26.2", HASH));
        P38TerrainCandidateAudit.ForwardTerrainModel model = new P38TerrainCandidateAudit.ForwardTerrainModel() {
            public String modelId() { return "controlled:synthetic_surface_v1"; }
            public List<Integer> predictedWorldSurface(long seed, int cx, int cz) {
                List<Integer> values = heights(cx);
                if (seed == 2731) return values;
                var result = new ArrayList<>(values);
                if (seed == 4444 && cx == 256) result.set(3, result.get(3) + 1);
                if (seed == 5555 && cx == 0) result.set(0, result.get(0) + 1);
                return result;
            }
        };
        var report = P38TerrainCandidateAudit.compare(observations,
                List.of(2731L, 4444L, 5555L), model);
        check(report.distinctRegions() == 3, "需3个相互独立的地形区块");
        check(!report.generatorVerified() && !report.complete64Recovered(),
                "匹配前向模型不能被升级为已获取世界真实完整Seed");
        check(report.candidates().get(0).verdict()
                == P38TerrainCandidateAudit.Verdict.CONDITIONAL_MATCH_UNVERIFIED,
                "受控正确候选只能称为模型条件一致");
        check(report.candidates().get(1).verdict()
                == P38TerrainCandidateAudit.Verdict.REJECTED_BY_HOLDOUT,
                "专门构造的仅留出组错候选必须被真正独立HOLDOUT否定");
        check(report.candidates().get(2).verdict()
                == P38TerrainCandidateAudit.Verdict.REJECTED_BY_FIT,
                "拟合组错候选必须先被FIT排除");

        var close = List.of(sample(0, "26.2", HASH),
                sample(1, "26.2", HASH), sample(2, "26.2", HASH));
        var insufficient = P38TerrainCandidateAudit.compare(close, List.of(2731L), model);
        check(insufficient.candidates().getFirst().verdict()
                == P38TerrainCandidateAudit.Verdict.INSUFFICIENT_INDEPENDENT_SAMPLES,
                "相邻区块不能冒充独立模型验证");
        expectFailure(() -> P38TerrainCandidateAudit.compare(
                List.of(sample(0, "26.2", HASH), sample(128,"26.2",HASH),
                        sample(256,"26.1.2",HASH)), List.of(2731L), model),
                "不同游戏版本不能混合进入地形候选验证");
        expectFailure(() -> P38TerrainCandidateAudit.compare(
                List.of(sample(0, "26.2", HASH), sample(128,"26.2",HASH),
                        sample(256,"26.2","b".repeat(64))), List.of(2731L), model),
                "不同世界HMAC不能混合进入候选验证");
        expectFailure(() -> P38TerrainCandidateAudit.compare(
                observations, List.of(2731L,2731L), model),
                "重复候选Seed不得伪造独立搜索数量");
        expectFailure(() -> P38TerrainCandidateAudit.compare(
                List.of(sample(0,"26.2",HASH),sample(0,"26.2",HASH),
                        sample(256,"26.2",HASH)), List.of(2731L), model),
                "重复实际观察区块不等于3条独立地形");

        System.out.println("P3.8 terrain candidate FIT/HOLDOUT model hypothesis smoke PASS (CONTROL ONLY)");
    }

    private static TerrainChunkSample sample(int cx, String version, String hash) {
        return new TerrainChunkSample(hash, "minecraft:overworld", version,
                cx, 0, -64, 320, 63, heights(cx), Instant.parse("2026-10-14T00:00:00Z"));
    }

    private static List<Integer> heights(int cx) {
        List<Integer> result = new ArrayList<>(16);
        for (int i=0;i<16;i++) result.add(68 + Math.floorMod(cx,19) + (i%4));
        return result;
    }

    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }

    private static void expectFailure(Runnable operation, String reason) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(reason);
    }
}
