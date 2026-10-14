package io.github.fxjcangku.worldseedlab.core.recognition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * P2.6 原创稀疏方块指纹分类器。没有读取村民、箱子、NBT、矿物分布或服务端元数据。
 *
 * <p>“材料组合相符”只表示应当近距离继续观察，不等于 Minecraft 结构起点已确定。
 * 自定义地图和玩家建造可能复制完全相同的材料，因此所有结果仅为候选。
 */
public final class StructureSignatureDetector {
    private StructureSignatureDetector() {}

    public static List<StructureCandidate> classify(Map<String, Integer> blocks,
                                                    int scannedCells) {
        Objects.requireNonNull(blocks);
        if (scannedCells < 32 || scannedCells > 8192) {
            throw new IllegalArgumentException("scan cell count outside supported range");
        }
        for (var e : blocks.entrySet()) {
            if (e.getValue() == null || e.getValue() < 0) {
                throw new IllegalArgumentException("negative signature count");
            }
        }
        List<StructureCandidate> found = new ArrayList<>();

        int prismarine = count(blocks, "minecraft:prismarine")
                + count(blocks, "minecraft:prismarine_bricks");
        int dark = count(blocks, "minecraft:dark_prismarine");
        int lamps = count(blocks, "minecraft:sea_lantern");
        if (prismarine >= 18 && dark + lamps >= 3) {
            Map<String, Integer> evidence = witnesses(blocks,
                    "minecraft:prismarine", "minecraft:prismarine_bricks",
                    "minecraft:dark_prismarine", "minecraft:sea_lantern");
            found.add(new StructureCandidate("minecraft:monument",
                    prismarine >= 35 && dark >= 4 && lamps >= 2
                            ? StructureCandidate.Grade.MATERIAL_PATTERN
                            : StructureCandidate.Grade.WEAK,
                    evidence, prismarine + dark + lamps,
                    "海洋神殿材料组合；仍可能来自人工建筑或自定义生成"));
        }

        int desertStone = count(blocks, "minecraft:sandstone")
                + count(blocks, "minecraft:smooth_sandstone")
                + count(blocks, "minecraft:cut_sandstone")
                + count(blocks, "minecraft:chiseled_sandstone");
        int patterned = count(blocks, "minecraft:orange_terracotta")
                + count(blocks, "minecraft:blue_terracotta");
        int carved = count(blocks, "minecraft:chiseled_sandstone")
                + count(blocks, "minecraft:cut_sandstone");
        if (desertStone >= 18 && patterned >= 3 && carved >= 3) {
            Map<String, Integer> evidence = witnesses(blocks,
                    "minecraft:sandstone", "minecraft:smooth_sandstone",
                    "minecraft:cut_sandstone", "minecraft:chiseled_sandstone",
                    "minecraft:orange_terracotta", "minecraft:blue_terracotta");
            found.add(new StructureCandidate("minecraft:desert_pyramid",
                    desertStone >= 32 && patterned >= 7 && carved >= 6
                            ? StructureCandidate.Grade.MATERIAL_PATTERN
                            : StructureCandidate.Grade.WEAK,
                    evidence, desertStone + patterned,
                    "沙漠神殿装饰组合；无法单凭材料确定自然生成或模板原点"));
        }

        // 沉船材料与普通船坞、村庄住宅高度重合，所以仅保留弱候选。
        int planks = suffixCount(blocks, "_planks");
        int stairs = suffixCount(blocks, "_stairs");
        int trapdoors = suffixCount(blocks, "_trapdoor");
        int water = count(blocks, "minecraft:water");
        if (planks >= 18 && stairs >= 4 && trapdoors >= 2 && water >= 12) {
            Map<String, Integer> evidence = new HashMap<>();
            for (var e : blocks.entrySet()) {
                String id = e.getKey();
                if ((id.endsWith("_planks") || id.endsWith("_stairs")
                        || id.endsWith("_trapdoor") || id.equals("minecraft:water"))
                        && e.getValue() > 0 && evidence.size() < 16) {
                    evidence.put(id, e.getValue());
                }
            }
            found.add(new StructureCandidate("minecraft:shipwreck",
                    StructureCandidate.Grade.WEAK, evidence,
                    planks + stairs + trapdoors,
                    "水域木质建筑线索；船坞、住宅同样可能命中，不可直接用于种子推导"));
        }

        /*
         * P3.8 水下遗迹：冷暖变种拥有不同的材料组合，但普通海床、
         * 玩家建筑以及自定义结构能够仿造它们。这里只产生WEAK材料线索。
         * 正例要求同时出现水体+特定建筑材料+至少一种装饰/风化变体，
         * 而不是仅因看到砂岩或圆石就宣称找到了天然生成的结构。
         */
        int warmTrim = count(blocks, "minecraft:sandstone_stairs")
                + count(blocks, "minecraft:sandstone_slab")
                + count(blocks, "minecraft:smooth_sandstone_stairs")
                + count(blocks, "minecraft:smooth_sandstone_slab")
                + count(blocks, "minecraft:cut_sandstone_slab");
        if (water >= 12 && desertStone >= 14 && warmTrim >= 4
                && patterned < 3) {
            Map<String, Integer> warm = witnesses(blocks,
                    "minecraft:sandstone", "minecraft:cut_sandstone",
                    "minecraft:smooth_sandstone", "minecraft:chiseled_sandstone",
                    "minecraft:sandstone_stairs", "minecraft:sandstone_slab",
                    "minecraft:smooth_sandstone_stairs", "minecraft:smooth_sandstone_slab",
                    "minecraft:cut_sandstone_slab", "minecraft:water");
            found.add(new StructureCandidate("minecraft:ocean_ruin_warm",
                    StructureCandidate.Grade.WEAK, warm,
                    desertStone + warmTrim,
                    "暖水海底遗迹候选：砂岩构件/台阶/半砖与水共现；可能是海床改造"));
        }

        int coldBricks = count(blocks, "minecraft:stone_bricks")
                + count(blocks, "minecraft:mossy_stone_bricks")
                + count(blocks, "minecraft:cracked_stone_bricks");
        int weathered = count(blocks, "minecraft:mossy_stone_bricks")
                + count(blocks, "minecraft:cracked_stone_bricks");
        int coldRuinsDebris = count(blocks, "minecraft:gravel")
                + count(blocks, "minecraft:cobblestone")
                + count(blocks, "minecraft:mossy_cobblestone");
        if (water >= 12 && coldBricks >= 16 && weathered >= 4
                && coldRuinsDebris >= 6) {
            Map<String, Integer> cold = witnesses(blocks,
                    "minecraft:stone_bricks", "minecraft:mossy_stone_bricks",
                    "minecraft:cracked_stone_bricks", "minecraft:gravel",
                    "minecraft:cobblestone", "minecraft:mossy_cobblestone",
                    "minecraft:water");
            found.add(new StructureCandidate("minecraft:ocean_ruin_cold",
                    StructureCandidate.Grade.WEAK, cold,
                    coldBricks + weathered,
                    "冷水海底遗迹候选：石砖风化组合与水共现；无法证明原版生成起点"));
        }

        // 海底废弃传送门和遗迹材料可能与玩家建筑完全重叠，始终WEAK。
        int obsidian = count(blocks, "minecraft:obsidian");
        int crying = count(blocks, "minecraft:crying_obsidian");
        int magma = count(blocks, "minecraft:magma_block");
        if (water >= 8 && obsidian >= 8 && crying >= 2 && magma >= 3) {
            found.add(new StructureCandidate("minecraft:ruined_portal",
                    StructureCandidate.Grade.WEAK,
                    witnesses(blocks, "minecraft:obsidian", "minecraft:crying_obsidian",
                            "minecraft:magma_block", "minecraft:water"),
                    obsidian + crying + magma,
                    "水下废弃传送门材料候选；并非结构自然来源验证"));
        }

        /*
         * 其它具有明显特殊材料的原版结构：只是跨环境的材料候选，
         * 本阶段没有独立的完整几何模板，不接入结构Seed约束。
         */
        int sculk = count(blocks, "minecraft:sculk")
                + count(blocks, "minecraft:sculk_bricks")
                + count(blocks, "minecraft:sculk_catalyst");
        int reinforced = count(blocks, "minecraft:reinforced_deepslate");
        if (sculk >= 15 && reinforced >= 2) {
            found.add(new StructureCandidate("minecraft:ancient_city",
                    StructureCandidate.Grade.WEAK,
                    witnesses(blocks, "minecraft:sculk",
                            "minecraft:sculk_bricks", "minecraft:sculk_catalyst",
                            "minecraft:reinforced_deepslate"),
                    sculk + reinforced,
                    "远古城市特殊材料线索；未知生成器与人工建造仍不可排除"));
        }

        int tuff = count(blocks, "minecraft:tuff_bricks")
                + count(blocks, "minecraft:polished_tuff");
        int copper = count(blocks, "minecraft:copper_grate")
                + count(blocks, "minecraft:copper_bulb")
                + count(blocks, "minecraft:exposed_copper_grate");
        int trialSpawner = count(blocks, "minecraft:trial_spawner");
        if (trialSpawner >= 1 && tuff >= 10 && copper >= 4) {
            found.add(new StructureCandidate("minecraft:trial_chambers",
                    StructureCandidate.Grade.WEAK,
                    witnesses(blocks, "minecraft:tuff_bricks", "minecraft:polished_tuff",
                            "minecraft:copper_grate", "minecraft:copper_bulb",
                            "minecraft:exposed_copper_grate", "minecraft:trial_spawner"),
                    tuff + copper + trialSpawner,
                    "试炼密室材料组合候选，不证明真实结构生成来源"));
        }

        int endCityPurpur = count(blocks, "minecraft:purpur_block")
                + count(blocks, "minecraft:purpur_pillar")
                + count(blocks, "minecraft:purpur_stairs");
        int endRod = count(blocks, "minecraft:end_rod");
        if (endCityPurpur >= 18 && endRod >= 3) {
            found.add(new StructureCandidate("minecraft:end_city",
                    StructureCandidate.Grade.WEAK,
                    witnesses(blocks, "minecraft:purpur_block",
                            "minecraft:purpur_pillar", "minecraft:purpur_stairs",
                            "minecraft:end_rod"),
                    endCityPurpur + endRod, "末地城材料候选；未核验自然来源和起始区块"));
        }

        int fortress = count(blocks, "minecraft:nether_bricks");
        int fortressTrim = count(blocks, "minecraft:nether_brick_fence")
                + count(blocks, "minecraft:nether_brick_stairs");
        if (fortress >= 20 && fortressTrim >= 8) {
            found.add(new StructureCandidate("minecraft:fortress",
                    StructureCandidate.Grade.WEAK,
                    witnesses(blocks, "minecraft:nether_bricks",
                            "minecraft:nether_brick_fence", "minecraft:nether_brick_stairs"),
                    fortress + fortressTrim, "下界要塞材料线索，不能排除玩家建筑"));
        }

        int bastion = count(blocks, "minecraft:polished_blackstone_bricks")
                + count(blocks, "minecraft:cracked_polished_blackstone_bricks");
        int gilded = count(blocks, "minecraft:gilded_blackstone");
        if (bastion >= 20 && gilded >= 3) {
            found.add(new StructureCandidate("minecraft:bastion_remnant",
                    StructureCandidate.Grade.WEAK,
                    witnesses(blocks, "minecraft:polished_blackstone_bricks",
                            "minecraft:cracked_polished_blackstone_bricks",
                            "minecraft:gilded_blackstone"),
                    bastion + gilded, "堡垒遗迹材料候选，未知Salt和生成器状态不变"));
        }
        return List.copyOf(found);
    }

    private static int count(Map<String, Integer> blocks, String key) {
        return blocks.getOrDefault(key, 0);
    }

    private static int suffixCount(Map<String, Integer> blocks, String suffix) {
        int total = 0;
        for (var e : blocks.entrySet()) {
            if (e.getKey().startsWith("minecraft:") && e.getKey().endsWith(suffix)) {
                total += e.getValue();
            }
        }
        return total;
    }

    private static Map<String, Integer> witnesses(Map<String, Integer> blocks, String... keys) {
        Map<String, Integer> result = new HashMap<>();
        for (String key : keys) {
            int n = count(blocks, key);
            if (n > 0) result.put(key, n);
        }
        return result;
    }
}
