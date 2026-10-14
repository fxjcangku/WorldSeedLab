package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.geometry.ClientVisibleDesertPyramidRaster;
import io.github.fxjcangku.worldseedlab.core.geometry.StructureFootprintInverter;
import io.github.fxjcangku.worldseedlab.core.solver.RealClientSeedRecoveryCli;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * P3.9 离线导入、客户端作用域、严格可见轮廓、独立region正反例。
 * 此处的局部方块是合成夹具；完整真实游戏客户端栅格另由P3.9
 * runServer+runClient+Z3工作流独立验证，不能以本测试替代实机结果。
 */
public final class P39RealSeedRecoverySmokeTest {
    private P39RealSeedRecoverySmokeTest() {}

    public static void main(String[] args) throws Exception {
        String version = System.getProperty("mcVersion", "26.2");
        Path root = Files.createTempDirectory("wsl-p39-recovery-");
        try {
            var empty = RealClientSeedRecoveryCli.inspect(root, version);
            check(empty.filesSeen() == 0 && empty.independentRegions(32) == 0,
                    "没有完整结构时不得凭空给出Seed");

            int[][] starts = {{-887,-879},{-876,875},{22,8},{885,871}};
            String fp = "a".repeat(64);
            for (int i = 0; i < 4; i++) {
                writeSyntheticClientFootprint(root.resolve("client-auto-desert-"+i+".properties"),
                        starts[i][0], starts[i][1], fp, version);
            }
            var actual = RealClientSeedRecoveryCli.inspect(root, version);
            check(actual.filesSeen() == 4 && actual.sites().size() == 4
                            && actual.independentRegions(32) == 4,
                    "必须独立识别至少四处不重复的完整21x21神殿轮廓");
            var input = RealClientSeedRecoveryCli.constraints(actual, 14357617,0,4096);
            check(input.constraints().size() == 4
                    && input.constraints().stream().filter(c ->
                        c.split()==io.github.fxjcangku.worldseedlab.core.solver
                                .StartPlacementConstraint.Split.FIT).count()==3
                    && input.constraints().stream().filter(c ->
                        c.split()==io.github.fxjcangku.worldseedlab.core.solver
                                .StartPlacementConstraint.Split.HOLDOUT).count()==1,
                    "三个独立FIT和一个HOLDOUT是已经划分的真实结构模型约束");
            var spec = input.models().get(RealClientSeedRecoveryCli.SET_ID);
            check(input.constraints().stream().allMatch(c -> c.matchesPlacement(2731L,spec)),
                    "真实受控Minecraft结构起点坐标应支持2731低48候选");
            check(input.constraints().stream().anyMatch(c -> !c.matchesPlacement(2732L,spec)),
                    "错误低48种子必须被实际离散起点坐标否决");
            writeSyntheticClientFootprint(root.resolve("client-auto-desert-mismatch.properties"),
                    1024,1024,"b".repeat(64),version);
            boolean rejected=false;
            try { RealClientSeedRecoveryCli.inspect(root, version); }
            catch (java.io.IOException correct) { rejected=true; }
            check(rejected, "禁止混合两个真实服务器HMAC世界身份");
            Files.delete(root.resolve("client-auto-desert-mismatch.properties"));
            Files.delete(root.resolve("client-auto-desert-2.properties"));
            Files.delete(root.resolve("client-auto-desert-3.properties"));
            var few = RealClientSeedRecoveryCli.inspect(root, version);
            check(few.independentRegions(32) == 2, "仅两座不够推导低48");
            boolean insufficient=false;
            try { RealClientSeedRecoveryCli.constraints(few, 14357617); }
            catch (IllegalArgumentException expected) { insufficient=true; }
            check(insufficient,"独立结构Region不足时不得调用Z3伪造破解");
        } finally {
            try(var paths=Files.walk(root)) {
                for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        System.out.println("P3.9 strict CLIENT raster auto-import, low48 model and negatives PASS: "
                + version + "（合成栅格，仅测试算法输入安全；真实游戏E2E独立）");
    }

    private static void writeSyntheticClientFootprint(Path path,int cx,int cz,
                                                       String fp,String version) throws Exception {
        int left=Math.multiplyExact(cx,16), north=Math.multiplyExact(cz,16);
        var roi=new StructureFootprintInverter.BlockBounds(
                left-4,north-4,left+24,north+24);
        int low=63,high=94;
        byte[] cells=new byte[31*31*32];
        for(int x=left;x<=left+20;x++)
            for(int z=north;z<=north+20;z++)
                if(x==left||x==left+20||z==north||z==north+20) {
                    cells[ClientVisibleDesertPyramidRaster.index(x,low,z,roi,low,high)]=
                            ClientVisibleDesertPyramidRaster.SANDSTONE;
                }
        for(int i=0;i<6;i++)
            cells[ClientVisibleDesertPyramidRaster.index(left+3+i,low+1,
                    north+8,roi,low,high)] =
                    ClientVisibleDesertPyramidRaster.ORANGE_TERRACOTTA;
        for(int i=0;i<4;i++)
            cells[ClientVisibleDesertPyramidRaster.index(left+3+i,low+2,
                    north+9,roi,low,high)] =
                    ClientVisibleDesertPyramidRaster.CARVED_OR_CUT;
        cells[ClientVisibleDesertPyramidRaster.index(left+10,low,north+10,roi,low,high)]=
                ClientVisibleDesertPyramidRaster.BLUE_TERRACOTTA;
        var raster = new ClientVisibleDesertPyramidRaster(
                new ClientVisibleDesertPyramidRaster.Scope(fp,version,"minecraft:overworld"),
                roi,low,high,cells);
        check(raster.audit().completeVisiblePerimeter(),
                "受控合成完整轮廓必须先通过原有边缘审计");
        Files.writeString(path,raster.encode()
                + "captureMode=P36_AUTOMATIC_EXPLICIT_OPT_IN\n"
                + "generatorProfile=UNKNOWN\nstructureStartKnown=false\n");
    }

    private static void check(boolean condition,String why) {
        if(!condition)throw new AssertionError(why);
    }
}
