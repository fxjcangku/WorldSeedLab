package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.compatibility.StructureObservation.ChunkBounds;
import io.github.fxjcangku.worldseedlab.core.geometry.ClientVisibleDesertPyramidRaster;
import io.github.fxjcangku.worldseedlab.core.geometry.StructureFootprintInverter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * P3.5 纯Java正反例：客户端可见方块栅格完整性/材料指纹/外侧空气
 * 和来源隔离。这里是合成方块场景，绝非真实Minecraft客户端联机验收。
 */
public final class P35ClientVisibleFootprintSmokeTest {
    private static final String WORLD = "a".repeat(64);
    private static final String DIM = "minecraft:overworld";
    private static final String SET = "minecraft:desert_pyramids";
    private static final String STRUCTURE = "minecraft:desert_pyramid";
    private static final int Y = 80;

    private P35ClientVisibleFootprintSmokeTest() {}

    public static void main(String[] args) throws Exception {
        for (var version : List.of("26.1.2", "26.2")) {
            var scope = new ClientVisibleDesertPyramidRaster.Scope(WORLD, version, DIM);
            var footprint = new StructureFootprintInverter.BlockBounds(-319,-511,-299,-491);
            byte[] cells = synthetic(footprint, Y, Y+31);
            var raster = new ClientVisibleDesertPyramidRaster(
                    scope, footprint, Y, Y+31, cells);
            var accepted = raster.audit();
            check(accepted.completeVisiblePerimeter(),
                    "完整四侧露天墙体与纹饰必须通过合成轮廓自检: "+accepted.reason());
            check(accepted.westEdge()==21 && accepted.eastEdge()==21
                    && accepted.northEdge()==21 && accepted.southEdge()==21,
                    "每边应有21格被实际读取的可见边界");
            var calibration = StructureFootprintInverter.Calibration.fromControlled(
                    "control_2731",version,DIM,SET,STRUCTURE,"north",
                    footprint,-20,-32,0);
            var inferred = raster.inferCalibratedHypotheses(List.of(calibration));
            check(inferred.possibleChunkCount()==1 && inferred.alternatives().equals(
                    List.of(new ChunkBounds(-20,-32,-20,-32))),
                    "客户端足迹->P2.9必须得到离散的正确负坐标");
            var alternative = StructureFootprintInverter.Calibration.fromControlled(
                    "control_second",version,DIM,SET,STRUCTURE,"west",
                    new StructureFootprintInverter.BlockBounds(-383,-511,-363,-491),
                    -20,-32,0);
            var many = raster.inferCalibratedHypotheses(List.of(calibration,alternative));
            check(many.possibleChunkCount()==2 && many.envelopeContainsFalsePositions(),
                    "两个朝向产生稀疏起点并集，不能把包络缺口变成合法起点");
            check(many.alternatives().stream().noneMatch(c -> c.contains(-18,-32)),
                    "包络中间空白位置不能获得StructureStart资格");

            Path archive = Files.createTempFile("wsl-p35-client-", ".properties");
            try {
                Files.writeString(archive,raster.encode());
                var replay = ClientVisibleDesertPyramidRaster.read(archive);
                check(replay.rasterSize()==raster.rasterSize()
                                && replay.audit().completeVisiblePerimeter()
                                && replay.scope().equals(scope),
                        "本地原始 CLIENT_OBSERVED 栅格往返读取不一致");
                check(!raster.encode().contains("StructureStart")
                                && !raster.encode().contains("seed="),
                        "客户端取证不能伪造服务端生成起点与Seed");
                Files.writeString(archive,raster.encode().replace(
                        "origin=CLIENT_OBSERVED_BLOCK_STATES",
                        "origin=CONTROL_WORLD_OBSERVED"));
                expectIo(() -> ClientVisibleDesertPyramidRaster.read(archive),
                        "服务端证据不得冒充客户端原始可见方块");
                Files.writeString(archive,raster.encode().replace(
                        "naturalOrigin=UNKNOWN","naturalOrigin=VERIFIED"));
                expectIo(() -> ClientVisibleDesertPyramidRaster.read(archive),
                        "客户端不能自行认证自然来源");
            } finally {
                Files.deleteIfExists(archive);
            }

            byte[] missingEast = cells.clone();
            for (int z=footprint.minZ(); z<=footprint.maxZ();z++) {
                for (int y=Y;y<=Y+31;y++) {
                    missingEast[ClientVisibleDesertPyramidRaster.index(
                            footprint.maxX()+1,y,z,footprint,Y,Y+31)] =
                            ClientVisibleDesertPyramidRaster.OTHER;
                }
            }
            var blocked = new ClientVisibleDesertPyramidRaster(
                    scope,footprint,Y,Y+31,missingEast);
            check(!blocked.audit().completeVisiblePerimeter()
                            && blocked.audit().eastEdge()==0,
                    "建筑东面被泥土遮挡应拒绝推导");
            expectReject(() -> blocked.inferCalibratedHypotheses(List.of(calibration)),
                    "遮挡不能被上游误升为起点候选");
            byte[] lost = cells.clone();
            lost[0] = -1;
            expectReject(() -> new ClientVisibleDesertPyramidRaster(
                    scope,footprint,Y,Y+31,lost), "未加载方块不得默认为空气");
            expectReject(() -> new ClientVisibleDesertPyramidRaster(
                    scope,footprint,Y,Y+31,new byte[cells.length-1]),
                    "区块未完整读取不能伪装完整区域");
            byte[] fake = cells.clone();
            for(int x=footprint.minX();x<=footprint.maxX();x++) {
                for(int z=footprint.minZ();z<=footprint.maxZ();z++) {
                    for(int y=Y;y<=Y+31;y++) {
                        int i=ClientVisibleDesertPyramidRaster.index(x,y,z,footprint,Y,Y+31);
                        if(fake[i]==ClientVisibleDesertPyramidRaster.ORANGE_TERRACOTTA
                                || fake[i]==ClientVisibleDesertPyramidRaster.BLUE_TERRACOTTA) {
                            fake[i]=ClientVisibleDesertPyramidRaster.OTHER;
                        }
                    }
                }
            }
            check(!new ClientVisibleDesertPyramidRaster(
                    scope,footprint,Y,Y+31,fake).audit().completeVisiblePerimeter(),
                    "普通砂岩围墙没有陶瓦纹饰不能归类沙漠神殿完整轮廓");
            check(ClientVisibleDesertPyramidRaster.classify("minecraft:diamond_ore")
                            == ClientVisibleDesertPyramidRaster.OTHER,
                    "矿物及假矿不应当做沙漠神殿图案");
            expectReject(() -> new ClientVisibleDesertPyramidRaster.Scope(
                    WORLD,"1.21.11",DIM),"旧版本取证不准跨版复用");
            System.out.println("P3.5 offline raster provenance, perimeter, occlusion, archive, "
                    + "negative chunk and P2.9 alternatives PASS: "+version);
        }
        System.out.println("注意：上述是合成BlockState夹具，必须单独执行真实runClient联机验收");
    }

    private static byte[] synthetic(
            StructureFootprintInverter.BlockBounds bounds,int minY,int maxY) {
        int width=bounds.maxX()-bounds.minX()+3;
        int depth=bounds.maxZ()-bounds.minZ()+3;
        byte[] b=new byte[width*depth*(maxY-minY+1)];
        for(int x=bounds.minX();x<=bounds.maxX();x++) {
            for(int z=bounds.minZ();z<=bounds.maxZ();z++) {
                if(x==bounds.minX() || x==bounds.maxX()
                        || z==bounds.minZ() || z==bounds.maxZ()) {
                    b[ClientVisibleDesertPyramidRaster.index(x,Y,z,bounds,minY,maxY)]
                            =ClientVisibleDesertPyramidRaster.SANDSTONE;
                }
            }
        }
        for(int i=0;i<9;i++) {
            int x=bounds.minX()+4+i, z=bounds.minZ()+8;
            b[ClientVisibleDesertPyramidRaster.index(x,Y+1,z,bounds,minY,maxY)] =
                    i<4?ClientVisibleDesertPyramidRaster.ORANGE_TERRACOTTA:
                    i<5?ClientVisibleDesertPyramidRaster.BLUE_TERRACOTTA:
                    ClientVisibleDesertPyramidRaster.CARVED_OR_CUT;
        }
        return b;
    }

    private static void check(boolean ok,String why) {
        if(!ok) throw new AssertionError(why);
    }
    private static void expectReject(Runnable task,String why) {
        try {task.run();}
        catch(IllegalArgumentException expected){return;}
        throw new AssertionError(why);
    }
    private interface ReadTask{void run() throws IOException;}
    private static void expectIo(ReadTask task,String why) throws IOException {
        try{task.run();}
        catch(IOException expected){return;}
        throw new AssertionError(why);
    }
}
