package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.generation.ClientHeightmapSafety;
import io.github.fxjcangku.worldseedlab.core.recognition.StructureCandidate;
import io.github.fxjcangku.worldseedlab.core.recognition.StructureSignatureDetector;
import java.util.HashMap;
import java.util.Map;

/**
 * P3.8：水下与跨维度多种结构材料候选的正反例。
 * 仅是合成材料统计，不冒充原版世界的自然StructureStart或完整建筑轮廓。
 */
public final class P38MultiStructureSmokeTest {
    private P38MultiStructureSmokeTest() {}

    public static void main(String[] args) {
        heightmapSafetyCases();
        positive("minecraft:monument", map(
                "minecraft:prismarine",35, "minecraft:prismarine_bricks",15,
                "minecraft:dark_prismarine",9, "minecraft:sea_lantern",5), 700, false);
        positive("minecraft:ocean_ruin_warm", map(
                "minecraft:sandstone",20, "minecraft:cut_sandstone",4,
                "minecraft:sandstone_stairs",5, "minecraft:water",44), 416, true);
        positive("minecraft:ocean_ruin_cold", map(
                "minecraft:stone_bricks",20, "minecraft:mossy_stone_bricks",7,
                "minecraft:gravel",8, "minecraft:water",52), 416, true);
        positive("minecraft:shipwreck", map(
                "minecraft:oak_planks",30, "minecraft:oak_stairs",8,
                "minecraft:oak_trapdoor",4, "minecraft:water",30), 416, true);
        positive("minecraft:ruined_portal", map(
                "minecraft:obsidian",12, "minecraft:crying_obsidian",3,
                "minecraft:magma_block",6, "minecraft:water",26), 416, true);
        positive("minecraft:ancient_city", map(
                "minecraft:sculk",24, "minecraft:sculk_shrieker",3,
                "minecraft:reinforced_deepslate",3), 416, true);
        positive("minecraft:trial_chambers", map(
                "minecraft:tuff_bricks",20, "minecraft:polished_tuff",7,
                "minecraft:copper_grate",6, "minecraft:trial_spawner",2), 416, true);
        positive("minecraft:end_city", map(
                "minecraft:purpur_block",25, "minecraft:end_rod",5), 416, true);
        positive("minecraft:fortress", map(
                "minecraft:nether_bricks",26, "minecraft:nether_brick_fence",10), 416, true);
        positive("minecraft:bastion_remnant", map(
                "minecraft:polished_blackstone_bricks",25,
                "minecraft:gilded_blackstone",4), 416, true);

        negative(map("minecraft:sandstone", 100, "minecraft:water", 90),
                "普通沙质海床不能直接推断海底遗迹");
        negative(map("minecraft:gravel", 40, "minecraft:cobblestone", 50,
                        "minecraft:water", 80), "普通砂砾/圆石海床不等于冷水遗迹");
        negative(map("minecraft:oak_planks", 30, "minecraft:water", 80),
                "水上普通木板平台不应成为沉船候选");
        negative(map("minecraft:prismarine", 12, "minecraft:water", 80),
                "零星海晶石不能证明海洋神殿");
        negative(map("minecraft:obsidian", 15, "minecraft:magma_block", 10),
                "不含哭泣黑曜石的地下建筑不得误当传送门");
        negative(map("minecraft:sculk", 60, "minecraft:stone", 60),
                "单纯幽匿地层不等于远古城市");
        negative(map("minecraft:diamond_ore", 100, "minecraft:deepslate_diamond_ore", 180),
                "矿石不能作为任何结构候选");
        System.out.println("P3.8 multi-structure material candidates and negatives PASS");
    }

    /** 真实客户端OCEAN_FLOOR缺失时必须拒绝造假海床，同时保持有界真实水面补偿。 */
    private static void heightmapSafetyCases() {
        if (ClientHeightmapSafety.usableOceanFloor(-64, 86, -65)
                || ClientHeightmapSafety.usableOceanFloor(-64, 86, 90)
                || ClientHeightmapSafety.usableOceanFloor(-64, -65, -65)) {
            throw new AssertionError("缺失、越界或倒置的客户端海床高度不得当成真值");
        }
        if (!ClientHeightmapSafety.usableOceanFloor(-64, 86, 70)
                || !ClientHeightmapSafety.usableOceanFloor(-64, 63, -63)) {
            throw new AssertionError("有效海床高度必须保留");
        }
        if (!"worldseedlab:heightmap_unavailable".equals(
                ClientHeightmapSafety.UNAVAILABLE_OCEAN_FLOOR_ID)) {
            throw new AssertionError("未观测海床不能标记为基岩");
        }
        var depths = ClientHeightmapSafety.fallbackWaterDepths();
        if (!depths.contains(-64) || !depths.contains(-48) || !depths.contains(-20)
                || depths.size() > 16) {
            throw new AssertionError("无海床高度时的水面补偿带不完整或超出预算");
        }
        System.out.println("P3.9 client heightmap sentinel and bounded aquatic fallback PASS");
    }

    private static void positive(String target, Map<String,Integer> materials,
                                 int scanned, boolean weak) {
        var classified = StructureSignatureDetector.classify(materials, scanned);
        var found = classified.stream().filter(v -> target.equals(v.structureId())).findFirst();
        if (found.isEmpty()) throw new AssertionError("未发现预期候选: " + target);
        StructureCandidate candidate = found.get();
        if (weak && candidate.grade() != StructureCandidate.Grade.WEAK) {
            throw new AssertionError("未经严格轮廓验证不能升级证据级别: " + target);
        }
        if (candidate.evidenceBlocks().isEmpty() || candidate.explanation().isBlank()) {
            throw new AssertionError("候选必须保存真实材料统计和中文审计说明");
        }
    }

    private static void negative(Map<String,Integer> materials, String reason) {
        if (!StructureSignatureDetector.classify(materials, 416).isEmpty()) {
            throw new AssertionError(reason);
        }
    }

    private static Map<String,Integer> map(Object... fields) {
        Map<String,Integer> map = new HashMap<>();
        if (fields.length % 2 != 0) throw new IllegalArgumentException("pairs");
        for (int i=0;i<fields.length;i+=2) {
            map.put((String) fields[i],(Integer) fields[i+1]);
        }
        return map;
    }
}
