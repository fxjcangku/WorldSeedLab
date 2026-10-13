package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceKind;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceOrigin;
import io.github.fxjcangku.worldseedlab.core.evidence.EvidenceRecord;
import io.github.fxjcangku.worldseedlab.core.generation.CoverageReportAppendix;
import io.github.fxjcangku.worldseedlab.core.generation.SamplingCoverage;
import io.github.fxjcangku.worldseedlab.core.probe.ChunkKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 覆盖足迹的纯 Java 正反例；无 Minecraft 游戏实例和网络依赖。 */
public final class CoverageSmokeTest {
    private CoverageSmokeTest() {
    }

    public static void main(String[] args) {
        String a = "a".repeat(64), b = "b".repeat(64);
        SamplingCoverage coverage = new SamplingCoverage(a, "minecraft:overworld");
        ChunkKey negativeChunk = new ChunkKey(-3, -40, 8);
        EvidenceRecord low = record(a, "minecraft:overworld",
                -46, -62, -638, EvidenceOrigin.CLIENT_OBSERVATION);
        EvidenceRecord high = record(a, "minecraft:overworld",
                -42, 66, -634, EvidenceOrigin.CLIENT_OBSERVATION);

        coverage.accepted(negativeChunk, List.of(low, high));
        coverage.accepted(negativeChunk, List.of(low));
        ChunkKey positiveChunk = new ChunkKey(34, 34, 8);
        coverage.accepted(positiveChunk, List.of(
                record(a, "minecraft:overworld", 546, 98, 546,
                        EvidenceOrigin.CLIENT_OBSERVATION)));
        coverage.abandonedUnloadedChunk();
        coverage.abandonedDetailChunk();
        coverage.diskBackpressure();

        var snapshot = coverage.snapshot();
        check(snapshot.uniqueAcceptedChunks() == 2, "one chunk counted once");
        check(snapshot.uniqueMacroRegions() == 2, "signed region segmentation");
        check(snapshot.acceptedQuartSamples() == 4, "samples from multiple strides count");
        check(snapshot.altitudeBandSamples().get(-64) == 2, "negative Y floor division");
        check(snapshot.altitudeBandSamples().get(64) == 1, "positive Y bin");
        check(snapshot.altitudeBandSamples().get(96) == 1, "higher altitude bin");
        check(snapshot.abandonedUnloadedChunks() == 1, "partial biome loss");
        check(snapshot.abandonedDetailChunks() == 1, "partial detail loss");
        check(snapshot.diskBackpressureCount() == 1, "backpressure count");

        try {
            snapshot.altitudeBandSamples().put(0, 10L);
            throw new AssertionError("snapshot must be immutable");
        } catch (UnsupportedOperationException expected) {
            // 不允许测试端篡改快照。
        }
        expectFail(() -> coverage.accepted(negativeChunk,
                List.of(record(b, "minecraft:overworld", -46, 4, -638,
                        EvidenceOrigin.CLIENT_OBSERVATION))), "cross-world rejected");
        expectFail(() -> coverage.accepted(negativeChunk,
                List.of(record(a, "minecraft:the_nether", -46, 4, -638,
                        EvidenceOrigin.CLIENT_OBSERVATION))), "cross-dimension rejected");
        expectFail(() -> coverage.accepted(negativeChunk,
                List.of(record(a, "minecraft:overworld", -46, 4, -638,
                        EvidenceOrigin.MANUAL_MARK))), "manual record not counted as quart sample");

        String report = CoverageReportAppendix.render(snapshot);
        check(report.contains("32×32"), "report should clarify macro regions");
        check(report.contains("不是整个世界的地图覆盖百分比"),
                "never claim whole-world coverage");
        check(!report.contains("minecraft:overworld") && !report.contains("-638"),
                "no precise coordinates or dimension hints in coverage appendix");
        System.out.println("WorldSeedLab sampling coverage smoke checks passed.");
    }

    private static EvidenceRecord record(String f, String d, int x, int y, int z,
                                         EvidenceOrigin origin) {
        return new EvidenceRecord(UUID.randomUUID(), f, d, "26.2",
                EvidenceKind.BIOME_OBSERVATION, "minecraft:plains",
                x, y, z, origin, Instant.parse("2026-10-14T00:00:00Z"), "test");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFail(Runnable action, String label) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(label);
    }
}
