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

        // P3.8：稀疏材料只能得出候选，海洋神殿/沉船/海底遗迹不自动成为结构起点。
        var warm = StructureSignatureDetector.classify(Map.of(
                "minecraft:sandstone", 24, "minecraft:sandstone_stairs", 8,
                "minecraft:sandstone_slab", 6, "minecraft:water", 36), 1600);
        require(warm.size() == 1
                && warm.getFirst().structureId().equals("minecraft:ocean_ruin_warm")
                && warm.getFirst().grade() == StructureCandidate.Grade.WEAK,
                "暖水海底遗迹材料组合仅应产生弱候选");
        var cold = StructureSignatureDetector.classify(Map.of(
                "minecraft:stone_bricks", 22, "minecraft:mossy_stone_bricks", 11,
                "minecraft:cracked_stone_bricks", 8, "minecraft:gravel", 19,
                "minecraft:water", 55), 1600);
        require(cold.size() == 1
                && cold.getFirst().structureId().equals("minecraft:ocean_ruin_cold"),
                "冷水海底遗迹必须依赖风化石砖+海床碎砾+水");
        require(StructureSignatureDetector.classify(Map.of(
                "minecraft:stone_bricks", 24, "minecraft:gravel", 20,
                "minecraft:water", 70), 1600).isEmpty(),
                "普通海底石砖/碎砾不能当作冷水遗迹");

        var portal = StructureSignatureDetector.classify(Map.of(
                "minecraft:obsidian", 11, "minecraft:crying_obsidian", 3,
                "minecraft:magma_block", 5, "minecraft:water", 20), 1600);
        require(portal.stream().anyMatch(c ->
                        c.structureId().equals("minecraft:ruined_portal")
                                && c.grade() == StructureCandidate.Grade.WEAK),
                "水下废弃传送门只属于弱材料线索");
        require(StructureSignatureDetector.classify(Map.of(
                "minecraft:tuff_bricks", 26, "minecraft:polished_tuff", 15,
                "minecraft:copper_grate", 11, "minecraft:trial_spawner", 2), 1600)
                .stream().anyMatch(c -> c.structureId().equals("minecraft:trial_chambers")),
                "试炼密室多材料候选");
        require(StructureSignatureDetector.classify(Map.of(
                "minecraft:sculk", 29, "minecraft:sculk_catalyst", 6,
                "minecraft:reinforced_deepslate", 4), 1600)
                .stream().anyMatch(c -> c.structureId().equals("minecraft:ancient_city")),
                "远古城市特殊材料候选");
        require(StructureSignatureDetector.classify(Map.of(
                "minecraft:purpur_block", 30, "minecraft:purpur_pillar", 5,
                "minecraft:end_rod", 5), 1600)
                .stream().anyMatch(c -> c.structureId().equals("minecraft:end_city")),
                "末地城特殊材料候选");
        require(StructureSignatureDetector.classify(Map.of(
                "minecraft:nether_bricks", 30, "minecraft:nether_brick_fence", 9), 1600)
                .stream().anyMatch(c -> c.structureId().equals("minecraft:fortress")),
                "下界要塞候选");
        require(StructureSignatureDetector.classify(Map.of(
                "minecraft:polished_blackstone_bricks", 31,
                "minecraft:gilded_blackstone", 5), 1600)
                .stream().anyMatch(c -> c.structureId().equals("minecraft:bastion_remnant")),
                "堡垒遗迹候选");

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
        require(row.contains("\"schema\":2") && row.contains("\"model_version\":2"),
                "P3.8升级后的材料索引须使用新版本，旧阴性不得阻断重扫");
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
            Path v1Journal = store.scopedFolder(world, "minecraft:overworld")
                    .resolve("structure-scanned-v1.csv");
            Files.createDirectories(v1Journal.getParent());
            Files.writeString(v1Journal, "-19,5\n", StandardCharsets.UTF_8);
            require(store.loadStructureScanned(world, "minecraft:overworld").isEmpty(),
                    "旧v1扫描阴性索引不应阻止新模型重新扫描");
            store.appendStructureScan(absence);
            require(store.loadStructureScanned(world, "minecraft:overworld")
                    .contains(chunk(-19, 5)), "empty candidate chunk still has scan journal");
            require(!Files.exists(store.scopedFolder(world, "minecraft:overworld")
                    .resolve("structure-candidates-v2.jsonl")),
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
                    .resolve("structure-candidates-v2.jsonl");
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
                    .resolve("structure-scanned-v2.csv");
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
