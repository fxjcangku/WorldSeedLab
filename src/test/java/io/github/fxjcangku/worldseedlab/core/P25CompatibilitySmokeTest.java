package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.compatibility.KnownSeedReference;
import io.github.fxjcangku.worldseedlab.core.compatibility.KnownSeedReference.Split;
import io.github.fxjcangku.worldseedlab.core.compatibility.KnownSeedReference.ReferenceOrigin;
import io.github.fxjcangku.worldseedlab.core.compatibility.KnownSeedReference.StartChunk;
import io.github.fxjcangku.worldseedlab.core.compatibility.StructureObservation;
import io.github.fxjcangku.worldseedlab.core.compatibility.StructureModelValidator;
import io.github.fxjcangku.worldseedlab.core.probe.ProbeConfigFile;
import io.github.fxjcangku.worldseedlab.core.probe.ProbeSettings;
import io.github.fxjcangku.worldseedlab.core.probe.DetailMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * P2.5 离线规范测试（不调用真实 Minecraft 世界生成器）。
 *
 * <p>自定义的预测函数只是模拟测试夹具，用来验证安全边界和留出分组，
 * 不代表 Mojang 26.x 已经验证，更不能用来向多人服务器求解种子。
 */
public final class P25CompatibilitySmokeTest {
    private P25CompatibilitySmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        String world = "1".repeat(64);
        String dimension = "minecraft:overworld";
        String structure = "minecraft:shipwreck";
        Instant now = Instant.parse("2026-10-14T00:00:00Z");

        var manual = new StructureObservation(world, dimension, "26.2", structure,
                -1200, 70, 500, StructureObservation.Origin.MANUAL_SIGHTING, null, now);
        expect(manual.possibleStartChunks() == null, "manual sighting cannot claim start origin");
        expectFail(() -> new StructureObservation(world, dimension, "26.2", structure,
                0, 60, 0, StructureObservation.Origin.MANUAL_SIGHTING,
                new StructureObservation.ChunkBounds(0, 0, 0, 0), now),
                "manual position must never be an exact structure origin");
        expectFail(() -> new StructureObservation(world, dimension, "26.2", structure,
                0, 60, 0, StructureObservation.Origin.GEOMETRY_ESTIMATE, null, now),
                "geometry observation requires a bounded candidate region");
        var bounds = new StructureObservation.ChunkBounds(-8, -4, -5, 1);
        expect(bounds.contains(-8, -4), "negative chunk boundary is inclusive");
        expect(!bounds.contains(-9, 0), "bounds exclude outside chunks");
        expect(!bounds.isExact(), "region cannot masquerade as a point");
        expectFail(() -> new StructureObservation.ChunkBounds(-10, 0, 100, 0),
                "unbounded start search must fail");

        expect(ProbeSettings.DEFAULT.detailMode() == DetailMode.OFF,
                "expensive block scans must be off on fresh install");
        Path configRoot = Files.createTempDirectory("wsl-p25-preferences-");
        try {
            var config = new ProbeConfigFile(configRoot);
            expect(config.load().detailMode() == DetailMode.OFF, "config missing defaults off");
            config.save(ProbeSettings.DEFAULT.nextDetailMode());
            expect(config.load().detailMode() == DetailMode.SURFACE,
                    "explicit user choice may persist without auto-arming capture");
        } finally {
            try (var files = Files.walk(configRoot)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }

        StructureModelValidator.StartPredictor synthetic = (seed, rx, rz) ->
                Optional.of(new StartChunk(rx * 32 + (int) Math.floorMod(seed, 10),
                        rz * 32 + 4));
        List<KnownSeedReference> controls = new ArrayList<>();
        long syntheticSeed = 123456L;
        for (int i = 0; i < 5; i++) {
            int rx = i - 3;
            int rz = 10 - i;
            controls.add(new KnownSeedReference(world, dimension, "26.2",
                    structure, syntheticSeed, rx, rz,
                    synthetic.predict(syntheticSeed, rx, rz),
                    i < 3 ? Split.TRAINING : Split.HOLDOUT,
                    ReferenceOrigin.SYNTHETIC_FIXTURE));
        }

        var unknown = StructureModelValidator.evaluate("26.2", dimension,
                structure, null, controls);
        expect(unknown.verdict() == StructureModelValidator.Verdict.UNKNOWN,
                "no reference implementation => unknown");
        var fixtureOnly = StructureModelValidator.evaluate("26.2", dimension,
                structure, synthetic, controls);
        expect(fixtureOnly.verdict() == StructureModelValidator.Verdict.UNKNOWN,
                "synthetic fixture must NEVER claim real game compatibility");

        // 该测试仅模拟“已由真实游戏确认”的来源标签，验证程序的判断分支。
        // 具体 Mojang 规则是否正确，还必须通过单人已知 Seed 世界实机对照。
        List<KnownSeedReference> declaredGameReferences = controls.stream().map(v ->
                new KnownSeedReference(v.worldFingerprint(), v.dimension(), v.gameVersion(),
                        v.structureId(), v.knownWorldSeed(), v.regionX(), v.regionZ(),
                        v.expectedStart(), v.split(), ReferenceOrigin.VERIFIED_GAME_REFERENCE))
                .toList();
        var okay = StructureModelValidator.evaluate("26.2", dimension,
                structure, synthetic, declaredGameReferences);
        expect(okay.verdict() == StructureModelValidator.Verdict.MODEL_COMPATIBLE,
                "game-verified reference path should recognize matching holdouts");
        expect(okay.trainingMatches() == 3 && okay.holdoutMatches() == 2,
                "training and holdout must be separated");

        var wrong = StructureModelValidator.evaluate("26.2", dimension, structure,
                (seed, rx, rz) -> Optional.of(new StartChunk(0, 0)), declaredGameReferences);
        expect(wrong.verdict() == StructureModelValidator.Verdict.MODEL_INCOMPATIBLE,
                "incorrect model must fail known-seed controls");
        var lacking = StructureModelValidator.evaluate("26.2", dimension,
                structure, synthetic, declaredGameReferences.subList(0, 4));
        expect(lacking.verdict() == StructureModelValidator.Verdict.INSUFFICIENT_EVIDENCE,
                "one holdout is insufficient");
        var duplicates = new ArrayList<>(declaredGameReferences);
        duplicates.add(declaredGameReferences.get(0));
        expectFail(() -> StructureModelValidator.evaluate("26.2", dimension,
                structure, synthetic, duplicates), "same region may not leak into holdout");
        expectFail(() -> StructureModelValidator.evaluate("26.1.2", dimension,
                structure, synthetic, declaredGameReferences), "mixed version samples fail closed");

        System.out.println("WorldSeedLab P2.5 compatibility quality checks passed.");
    }

    private static void expect(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void expectFail(Runnable operation, String message) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
