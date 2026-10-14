
package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.CandidateSeedSearch;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadMath;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import io.github.fxjcangku.worldseedlab.core.solver.SolverResearchInput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 解析用户显式提供的离线结构约束，不允许未核实的人工目击混入精确起点。
 * 这里的所有约束由受控合成 Seed 生成，仅验证工程输入和输出链路。
 */
public final class P27ResearchInputSmokeTest {
    private P27ResearchInputSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("wsl-p27-input");
        try {
            String world = "f".repeat(64);
            var spec = new RandomSpreadSpec("minecraft:research_set", 32, 8,
                    RandomSpreadSpec.Distribution.LINEAR, -42);
            long seed = 2731L;
            var a = RandomSpreadMath.potentialChunk(seed, -5, 8, spec);
            var b = RandomSpreadMath.potentialChunk(seed, 13, -17, spec);
            var c = RandomSpreadMath.potentialChunk(seed, 25, 3, spec);
            String template = String.join("\n",
                    "worldFingerprint=" + world,
                    "gameVersion=26.2",
                    "dimension=minecraft:overworld",
                    "models.count=1",
                    "model.0.id=minecraft:research_set",
                    "model.0.spacing=32",
                    "model.0.separation=8",
                    "model.0.distribution=LINEAR",
                    "model.0.salt=-42",
                    "constraints.count=3",
                    exact(0, "FIT", a.x(), a.z()),
                    exact(1, "FIT", b.x(), b.z()),
                    exact(2, "HOLDOUT", c.x(), c.z()),
                    "search.startLow48=0",
                    "search.endExclusive=5000",
                    "search.maxEvaluations=5000",
                    "search.maxResults=64",
                    "search.timeoutMillis=30000",
                    "");
            Path input = directory.resolve("sample.properties");
            Files.writeString(input, template);
            var parsed = SolverResearchInput.load(input);
            if (parsed.models().size() != 1 || parsed.constraints().size() != 3) {
                throw new AssertionError("expected model and 3 scoped exact constraints");
            }
            var result = CandidateSeedSearch.search(parsed.models(),
                    parsed.constraints(), parsed.searchWindow(), () -> false);
            if (result.candidates().stream().noneMatch(cand -> cand.seedLow48() == seed)) {
                throw new AssertionError("known low48 candidate lost during properties roundtrip");
            }

            Path wrong = directory.resolve("manual.properties");
            Files.writeString(wrong, template.replace(
                    "constraint.0.origin=REFERENCE_FROM_CONTROL_WORLD",
                    "constraint.0.origin=MANUAL_SIGHTING"));
            try {
                SolverResearchInput.load(wrong);
                throw new AssertionError("manual sighting must not become a start constraint");
            } catch (IllegalArgumentException expected) {
                // 意料中的 Fail Closed。
            }
            Path huge = directory.resolve("huge.properties");
            Files.writeString(huge, "x".repeat(65_537));
            try {
                SolverResearchInput.load(huge);
                throw new AssertionError("oversized research manifest should be rejected");
            } catch (java.io.IOException expected) {
                // 限制单文件大小，避免误把大型真实证据日志当作配置。
            }
            System.out.println("WorldSeedLab P2.7 offline input and bounded search checks passed.");
        } finally {
            try (var walk = Files.walk(directory)) {
                for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(item);
                }
            }
        }
    }

    private static String exact(int index, String split, int x, int z) {
        String p = "constraint." + index + ".";
        return String.join("\n",
                p + "structureSet=minecraft:research_set",
                p + "minChunkX=" + x,
                p + "minChunkZ=" + z,
                p + "maxChunkX=" + x,
                p + "maxChunkZ=" + z,
                p + "origin=REFERENCE_FROM_CONTROL_WORLD",
                p + "split=" + split);
    }
}
