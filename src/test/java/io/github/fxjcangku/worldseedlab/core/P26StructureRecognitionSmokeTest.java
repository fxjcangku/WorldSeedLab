package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.probe.AsyncEvidenceWriter;
import io.github.fxjcangku.worldseedlab.core.probe.CompletedChunkStore;
import io.github.fxjcangku.worldseedlab.core.recognition.CandidateClusterIndex;
import io.github.fxjcangku.worldseedlab.core.recognition.StructureCandidate;
import io.github.fxjcangku.worldseedlab.core.recognition.StructureEvidenceJson;
import io.github.fxjcangku.worldseedlab.core.recognition.StructureScanEvidence;
import io.github.fxjcangku.worldseedlab.core.recognition.StructureSignatureDetector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P2.6 原创结构材料证据正反例。
 * 所有方块数值均是合成夹具，不能冒充 Minecraft 的真实结构起点或已恢复 Seed。
 */
public final class P26StructureRecognitionSmokeTest {
    private P26StructureRecognitionSmokeTest() {}

    public static void main(String[] args) throws Exception {
        Map<String, Integer> temple = Map.of(
                "minecraft:sandstone", 30,
                "minecraft:cut_sandstone", 7,
                "minecraft:chiseled_sandstone", 8,
                "minecraft:orange_terracotta", 10
        );
        var desert = StructureSignatureDetector.classify(temple, 480);
        require(desert.size() == 1
                && desert.get(0).structureId().equals("minecraft:desert_pyramid"),
                "sandstone + orange terracotta should be a desert candidate");
        require(desert.get(0).grade() == StructureCandidate.Grade.MATERIAL_PATTERN,
                "stronger multi-material pattern should have material grade");

        Map<String, Integer> monument = Map.of(
                "minecraft:prismarine", 35,
                "minecraft:prismarine_bricks", 14,
                "minecraft:dark_prismarine", 9,
                "minecraft:sea_lantern", 4
        );
        var ocean = StructureSignatureDetector.classify(monument, 416);
        require(ocean.size() == 1
                && ocean.get(0).structureId().equals("minecraft:monument"),
                "prismarine family + lantern pattern");

        Map<String, Integer> fakeOres = Map.of(
                "minecraft:diamond_ore", 300,
                "minecraft:deepslate_diamond_ore", 700,
                "minecraft:ancient_debris", 55,
                "minecraft:emerald_ore", 600
        );
        require(StructureSignatureDetector.classify(fakeOres, 416).isEmpty(),
                "fake ores cannot produce structure candidates");

        Map<String, Integer> unrelated = Map.of(
                "minecraft:stone", 160, "minecraft:oak_planks", 17,
                "minecraft:diamond_ore", 350, "minecraft:water", 55);
        require(StructureSignatureDetector.classify(unrelated, 416).isEmpty(),
                "ordinary block noise is insufficient");

        Map<String, Integer> ship = Map.of(
                "minecraft:oak_planks", 28, "minecraft:oak_stairs", 9,
                "minecraft:oak_trapdoor", 5, "minecraft:water", 50,
                "minecraft:diamond_ore", 99);
        var weakShip = StructureSignatureDetector.classify(ship, 416);
        require(weakShip.size() == 1
                && weakShip.get(0).structureId().equals("minecraft:shipwreck")
                && weakShip.get(0).grade() == StructureCandidate.Grade.WEAK,
                "ambiguous shipwreck must always remain a weak candidate");
        require(weakShip.get(0).evidenceBlocks().keySet().stream()
                .noneMatch(x -> x.contains("_ore")), "ores may not enter material witness list");

        expectFail(() -> new StructureCandidate("minecraft:monument",
                StructureCandidate.Grade.WEAK, fakeOres, 700,
                "forbidden ore-based confidence"), "ore evidence rejected in data layer");
        var clusters = new CandidateClusterIndex();
        int first = clusters.add("minecraft:monument", -4, 2);
        require(clusters.add("minecraft:monument", -3, 3) == first,
                "adjacent chunks are only one approximate group");
        require(clusters.add("minecraft:desert_pyramid", -4, 2) != first,
                "different structure kinds must not collapse");
        require(clusters.add("minecraft:monument", 65, 90) != first,
                "distant candidates are separate groups");
        require(clusters.totalGroups() == 3, "group counts");

        String world = "c".repeat(64), other = "d".repeat(64);
        Instant now = Instant.parse("2026-10-14T00:00:00Z");
        var candidate = new StructureScanEvidence(world, "minecraft:overworld", "26.2",
                -20, 5, 416, 29, 91, ocean, now);
        var absence = new StructureScanEvidence(world, "minecraft:overworld", "26.2",
                -19, 5, 416, 29, 91, List.of(), now);
        String row = StructureEvidenceJson.line(candidate);
        require(row.contains("\"natural_origin_verified\":false")
                        && row.contains("\"structure_start_known\":false")
                        && row.contains("\"uses_ore_seed_evidence\":false"),
                "candidate cannot claim server generation facts");
        require(row.indexOf('\n') == -1, "JSONL rows cannot contain line breaks");
        require(!row.contains("diamond_ore"), "fake ores must never appear in candidate JSON");
        expectFail(() -> new StructureScanEvidence(other,
                        "minecraft:overworld", "26.2", 0, 0,
                        2, 0, 4, ocean, now),
                "incomplete scans may not be saved");

        Path directory = Files.createTempDirectory("worldseedlab-p26-");
        try {
            var store = new CompletedChunkStore(directory);
            require(store.loadStructureScanned(world, "minecraft:overworld").isEmpty(),
                    "no scan journal on new world");
            store.appendStructureScan(absence);
            require(store.loadStructureScanned(world, "minecraft:overworld")
                    .contains(chunk(-19, 5)), "empty candidate chunk still has scan journal");
            require(!Files.exists(store.scopedFolder(world, "minecraft:overworld")
                    .resolve("structure-candidates-v1.jsonl")),
                    "empty scan does not produce a misleading candidate");

            var writer = new AsyncEvidenceWriter(store, world, "minecraft:overworld");
            require(writer.offerStructureScan(candidate), "accept matching scoped candidate");
            var wrongWorld = new StructureScanEvidence(other, "minecraft:overworld",
                    "26.2", -20, 5, 416, 29, 91, ocean, now);
            require(!writer.offerStructureScan(wrongWorld),
                    "reject a candidate from another world");
            require(writer.closeAndAwait(3000), "flush bounded async writer");
            require(writer.writtenStructureScans() == 1,
                    "one successful candidate-bearing scan");
            require(writer.writtenStructureCandidates() == 1,
                    "one candidate record successfully written");
            require(store.loadStructureScanned(world, "minecraft:overworld")
                    .contains(chunk(-20, 5)), "candidate journal is durable");
            var candidateFile = store.scopedFolder(world, "minecraft:overworld")
                    .resolve("structure-candidates-v1.jsonl");
            require(Files.readAllLines(candidateFile, StandardCharsets.UTF_8).size() == 1,
                    "one scoped candidate JSONL row");
            require(store.loadStructureScanned(world, "minecraft:the_nether").isEmpty(),
                    "dimension-scoped candidate journal");
            require(store.loadStructureScanned(other, "minecraft:overworld").isEmpty(),
                    "world-scoped candidate journal");

            // 结构候选索引损坏时应只影响可选结构子系统：
            // P1 已完成区块索引的独立读取不能受到该错误污染。
            Path corrupted = directory.resolve("corrupted");
            var optionalStore = new CompletedChunkStore(corrupted);
            Path damagedJournal = optionalStore.scopedFolder(world, "minecraft:overworld")
                    .resolve("structure-scanned-v1.csv");
            Files.createDirectories(damagedJournal.getParent());
            Files.writeString(damagedJournal, "broken-coordinate\n", StandardCharsets.UTF_8);
            boolean structureIndexRejected = false;
            try {
                optionalStore.loadStructureScanned(world, "minecraft:overworld");
            } catch (java.io.IOException expected) {
                structureIndexRejected = true;
            }
            require(structureIndexRejected, "corrupt optional index must be detected");
            require(optionalStore.loadCompleted(world, "minecraft:overworld").isEmpty(),
                    "base P1 chunk index remains independently usable");

        } finally {
            try (var walk = Files.walk(directory)) {
                for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        System.out.println("WorldSeedLab P2.6 structure candidate smoke checks passed.");
    }

    private static long chunk(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void expectFail(Runnable op, String reason) {
        try { op.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(reason);
    }
}
