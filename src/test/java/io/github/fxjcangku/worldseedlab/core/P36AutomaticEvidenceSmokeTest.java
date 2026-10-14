package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.geometry.AutoDesertFootprintPlan;
import io.github.fxjcangku.worldseedlab.core.geometry.ClientVisibleDesertPyramidRaster;
import io.github.fxjcangku.worldseedlab.core.geometry.StructureFootprintInverter;
import java.nio.file.Files;
import java.util.List;
import io.github.fxjcangku.worldseedlab.core.probe.BoundedChunkHistory;

/**
 * P3.6 纯Java自动粗ROI和既有P3.5真实可见轮廓接口的正反例。
 * 该测试不是“客户端自动发现已完成”的声明；真实runClient必须独立验收。
 */
public final class P36AutomaticEvidenceSmokeTest {
    private static final String FINGERPRINT = "c".repeat(64);
    private static final String DIMENSION = "minecraft:overworld";
    private P36AutomaticEvidenceSmokeTest() {}

    public static void main(String[] args) throws Exception {
        // 真正长期跑图必须能继续扫描4096个以上的不同区块，且缓存不会膨胀。
        expectFailure(() -> new BoundedChunkHistory(0), "禁止无容量扫描历史");
        BoundedChunkHistory recent = new BoundedChunkHistory(4096);
        for (long chunk = 0; chunk < 4096; chunk++) {
            check(recent.remember(chunk), "首次完成的区块必须记账");
        }
        check(recent.retained() == 4096 && recent.evictions() == 0,
                "首次4096个扫描区块不得提前淘汰或停机");
        check(!recent.remember(4095L), "近期同一区块不得重复完成");
        check(recent.remember(4096L) && recent.retained() == 4096
                        && recent.evictions() == 1
                        && !recent.contains(0L) && recent.contains(4096L),
                "超过上限时应滚动淘汰最旧区块，不能停止整轮P3.6");
        for (long chunk = 4097; chunk < 100_000; chunk++) {
            check(recent.remember(chunk), "长时间跑图新发现区块应继续记录");
        }
        check(recent.retained() == 4096 && recent.evictions() == 100_000L-4096L
                        && !recent.contains(1L) && recent.contains(99_999L),
                "十万区块滚动缓存必须严格有界，历史淘汰准确");
        recent.clear();
        check(recent.retained() == 0 && recent.evictions() == 0
                        && recent.remember(-1L) && recent.remember(0L),
                "新世界必须清空历史；负数和零区块键不能发生碰撞");

        for (String version : List.of("26.1.2", "26.2")) {
            // 先前以玩家Y决定材料探测带，在鞘翅高空会错过地表蓝陶瓦。
            var surfaceBand = AutoDesertFootprintPlan.aroundObservedSurface(79, -64, 320);
            check(surfaceBand.equals(new AutoDesertFootprintPlan.ScanBand(55, 86))
                            && surfaceBand.includes(63),
                    "真实地表下方的蓝陶瓦必须纳入32格扫描带");
            check(AutoDesertFootprintPlan.aroundObservedSurface(79,-64,320)
                            .equals(surfaceBand),
                    "同一区块地表高度相同，搜索带不应依赖玩家高度");
            check(AutoDesertFootprintPlan.aroundObservedSurface(315,-64,320)
                            .equals(new AutoDesertFootprintPlan.ScanBand(288,319)),
                    "建造高度顶端必须裁剪出完整合法32格带");
            check(AutoDesertFootprintPlan.aroundObservedSurface(-80,-64,320)
                            .equals(new AutoDesertFootprintPlan.ScanBand(-64,-33)),
                    "建造高度底端必须安全裁剪");
            expectFailure(() -> AutoDesertFootprintPlan.aroundObservedSurface(20,0,31),
                    "维度不足32格必须保守拒绝");
            var anchor = new AutoDesertFootprintPlan.BlueTerracottaAnchor(-14182,63,-14054);
            var survey = AutoDesertFootprintPlan.fromObservedBlue(anchor,-64,320);
            check(survey.roughBounds().equals(
                    new StructureFootprintInverter.BlockBounds(-14196,-14068,-14168,-14040)),
                    "蓝色陶瓦候选仅构成P3.5兼容的29x29粗ROI，不是游戏的StructureStart");
            check(survey.minY()==63 && survey.maxY()==94,
                    "高度范围必须从客户端真实蓝色陶瓦确定");
            var actual = new StructureFootprintInverter.BlockBounds(
                    -14192,-14064,-14172,-14044);
            var scope = new ClientVisibleDesertPyramidRaster.Scope(
                    FINGERPRINT,version,DIMENSION);
            byte[] categories = synthetic(survey.roughBounds(),actual,
                    survey.minY(),survey.maxY());
            var raster = new ClientVisibleDesertPyramidRaster(scope,
                    survey.roughBounds(),survey.minY(),survey.maxY(),categories);
            check(raster.audit().completeVisiblePerimeter()
                            && raster.audit().matchedFootprints()==1
                            && raster.discoveredFootprint().equals(actual),
                    "自动粗ROI必须使用全部实际格子找到唯一紧致建筑外缘");
            var calibration = StructureFootprintInverter.Calibration.fromControlled(
                    "p36_control_"+version.replace(".","_"),version,DIMENSION,
                    "minecraft:desert_pyramids","minecraft:desert_pyramid",
                    "north",actual,-887,-879,0);
            var hypotheses = raster.inferCalibratedHypotheses(List.of(calibration));
            check(hypotheses.possibleChunkCount()==1
                            && hypotheses.alternatives().getFirst().contains(-887,-879),
                    "P3.6只复用已经验证的离散几何候选，不直接恢复完整64位Seed");

            // 单独验证已有 P3.5 读取器可以安全回放自动导出的本地证据。
            var archive = Files.createTempFile("p36-client-", ".properties");
            try {
                Files.writeString(archive,raster.encode()
                        + "captureMode=P36_AUTOMATIC_EXPLICIT_OPT_IN\n"
                        + "generatorProfile=UNKNOWN\n"
                        + "structureStartKnown=false\n");
                var parsed = ClientVisibleDesertPyramidRaster.read(archive);
                check(parsed.audit().completeVisiblePerimeter()
                        && parsed.scope().equals(scope),
                        "自动导出必须能被本地P3.5复核器读取且不伪造来源");
                Files.writeString(archive,raster.encode().replace(
                        "origin=CLIENT_OBSERVED_BLOCK_STATES",
                        "origin=CONTROL_WORLD_POSTHOC_FULL"));
                try {
                    ClientVisibleDesertPyramidRaster.read(archive);
                    throw new AssertionError("服务端真值不能冒充客户端自动证据");
                } catch (java.io.IOException expected) {
                    // 正确拒绝未知来源。
                }
            } finally {
                Files.deleteIfExists(archive);
            }
            var negative = categories.clone();
            negative[ClientVisibleDesertPyramidRaster.index(
                    actual.minX(),survey.minY(),actual.minZ()+5,
                    survey.roughBounds(),survey.minY(),survey.maxY())]
                    = ClientVisibleDesertPyramidRaster.AIR;
            check(!new ClientVisibleDesertPyramidRaster(scope,survey.roughBounds(),
                    survey.minY(),survey.maxY(),negative).audit().completeVisiblePerimeter(),
                    "单列外边破损不得视作完整起点");

            expectFailure(() -> AutoDesertFootprintPlan.fromObservedBlue(
                    new AutoDesertFootprintPlan.BlueTerracottaAnchor(12,-60,14),-64, -32),
                    "蓝陶瓦太接近建造高度上限必须拒绝");
            expectFailure(() -> AutoDesertFootprintPlan.fromObservedBlue(
                    new AutoDesertFootprintPlan.BlueTerracottaAnchor(Integer.MAX_VALUE,50,0),
                    -64,320),"超大坐标必须失败关闭");
            System.out.println("P3.6 auto plan+real P3.5 geometry archive and negatives PASS: "+version);
        }
        System.out.println("提醒：这只验证自动粗ROI数学与安全，不代表真实跑图自动发现已验收");
    }

    private static byte[] synthetic(
            StructureFootprintInverter.BlockBounds roi,
            StructureFootprintInverter.BlockBounds actual, int minY, int maxY) {
        byte[] voxels = new byte[31*31*32];
        for(int x=actual.minX();x<=actual.maxX();x++) {
            for(int z=actual.minZ();z<=actual.maxZ();z++) {
                if(x==actual.minX()||x==actual.maxX()
                        ||z==actual.minZ()||z==actual.maxZ()) {
                    voxels[ClientVisibleDesertPyramidRaster.index(
                            x,minY,z,roi,minY,maxY)] =
                            ClientVisibleDesertPyramidRaster.SANDSTONE;
                }
            }
        }
        int bx=actual.minX()+10, bz=actual.minZ()+10;
        voxels[ClientVisibleDesertPyramidRaster.index(bx,minY,bz,roi,minY,maxY)] =
                ClientVisibleDesertPyramidRaster.BLUE_TERRACOTTA;
        for (int k=0;k<7;k++) {
            voxels[ClientVisibleDesertPyramidRaster.index(
                    actual.minX()+4+k,minY+1,actual.minZ()+8,roi,minY,maxY)] =
                    k<4 ? ClientVisibleDesertPyramidRaster.ORANGE_TERRACOTTA
                        : ClientVisibleDesertPyramidRaster.CARVED_OR_CUT;
        }
        return voxels;
    }

    private static void check(boolean good,String message) {
        if(!good)throw new AssertionError(message);
    }
    private static void expectFailure(Runnable action,String message) {
        try { action.run(); }
        catch (IllegalArgumentException expected) {return;}
        throw new AssertionError(message);
    }
}
