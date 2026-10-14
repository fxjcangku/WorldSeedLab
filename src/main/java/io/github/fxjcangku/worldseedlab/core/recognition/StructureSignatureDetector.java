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
        if (planks >= 24 && stairs >= 6 && trapdoors >= 3 && water >= 14) {
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
