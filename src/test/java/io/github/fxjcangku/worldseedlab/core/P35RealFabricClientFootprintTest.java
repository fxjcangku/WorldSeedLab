package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.geometry.ClientVisibleDesertPyramidRaster;
import io.github.fxjcangku.worldseedlab.core.geometry.StructureFootprintInverter;
import io.github.fxjcangku.worldseedlab.core.solver.CandidateSeedSearch;
import io.github.fxjcangku.worldseedlab.core.solver.LocalSmtSeedSolver;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadMath;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import io.github.fxjcangku.worldseedlab.core.solver.SolverResearchInput;
import io.github.fxjcangku.worldseedlab.core.solver.StartPlacementConstraint;
import io.github.fxjcangku.worldseedlab.core.solver.SymbolicRandomSpread;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 仅在真正的Fabric GUI与受控Minecraft服务端已分别跑完后执行。
 *
 * <p>严格先读取客户端四份 CLIENT_OBSERVED 栅格并独立验证表面，
 * 最后才读取服务端保存的StructureStart NBT真值。测试服务端可以导航，
 * 但真值从不被送到客户端Controller，不能混作玩家可见证据。
 * 输入来自真实游戏的断言与纯Java合成SmokeTest严格分开。
 */
public final class P35RealFabricClientFootprintTest {
    private static final String SET = "minecraft:desert_pyramids";
    private static final String TYPE = "minecraft:desert_pyramid";
    private static final String DIM = "minecraft:overworld";
    private static final long SEED = 2731L;
    private P35RealFabricClientFootprintTest() {}

    private record TrueStart(int x, int z, int orientation) {}

    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("真实客户端目录与Minecraft版本");
        String version=args[1];
        if(!"26.1.2".equals(version) && !"26.2".equals(version)) {
            throw new IllegalArgumentException("P3.5版本非法");
        }
        Path directory=Path.of(args[0]);
        List<ClientVisibleDesertPyramidRaster> captured=new ArrayList<>();
        for(int i=0;i<4;i++) {
            Path source=directory.resolve("client-footprint-"+i+".properties");
            if(!Files.isRegularFile(source)) {
                throw new AssertionError("没有真正游戏聊天命令写出的客户端栅格 "+i);
            }
            var c=ClientVisibleDesertPyramidRaster.read(source);
            if(!version.equals(c.scope().gameVersion())
                    || !DIM.equals(c.scope().dimension())
                    || (i>0 && !c.scope().equals(captured.getFirst().scope()))) {
                throw new AssertionError("真实客户端栅格版本/世界纪元/维度混杂");
            }
            var audit=c.audit();
            System.out.println("[P35] CLIENT_OBSERVED raster "+i+": "+audit
                    + " rasterCells="+c.rasterSize());
            if(!audit.completeVisiblePerimeter()) {
                throw new AssertionError("第"+i+"处真实可见建筑并未形成可证明完整边界："
                        +audit.reason());
            }
            var discovered = c.discoveredFootprint();
            int width=discovered.maxX()-discovered.minX()+1;
            int depth=discovered.maxZ()-discovered.minZ()+1;
            if(width!=21 || depth!=21) {
                throw new AssertionError("实际受控原版沙漠神殿轮廓应当实测为21x21");
            }
            captured.add(c);
        }

        // 只有四份客户端证据全部通过之后，才执行服务端Anvil独立真值复核。
        Path trueCsv=directory.resolve("posthoc-real-structure-starts.csv");
        var raw=Files.readAllLines(trueCsv);
        if(raw.isEmpty() || !raw.getFirst().equals("chunk_x,chunk_z,orientation")) {
            throw new AssertionError("未取得客户端独立的真实StructureStart CSV");
        }
        var starts=new HashMap<String,TrueStart>();
        for(var line:raw.subList(1,raw.size())) {
            if(line.isBlank()) continue;
            var fields=line.split(",",-1);
            if(fields.length!=3) throw new AssertionError("真实结构NBT格式错误");
            var s=new TrueStart(Integer.parseInt(fields[0]),Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]));
            if(starts.put(s.x()+":"+s.z(),s)!=null) {
                throw new AssertionError("重复的受控NBT结构起点不能重复计数");
            }
        }
        var observedStarts=new ArrayList<TrueStart>();
        for(var c:captured) {
            var discovered = c.discoveredFootprint();
            int x=Math.floorDiv(discovered.minX(),16);
            int z=Math.floorDiv(discovered.minZ(),16);
            var truth=starts.get(x+":"+z);
            if(truth==null) {
                throw new AssertionError("CLIENT轮廓未涵盖任何独立保存的真实结构NBT起点");
            }
            observedStarts.add(truth);
        }
        if(new HashSet<>(observedStarts).size()!=4) {
            throw new AssertionError("四份客户端轮廓应为四个独立真实结构");
        }
        if(observedStarts.stream().map(TrueStart::orientation).distinct().count()<2) {
            throw new AssertionError("受控建筑朝向不足2种，不能验证旋转差异");
        }

        var model=new RandomSpreadSpec(SET,32,8,
                RandomSpreadSpec.Distribution.LINEAR,14357617);
        var wrongSalt=new RandomSpreadSpec(SET,32,8,
                RandomSpreadSpec.Distribution.LINEAR,14357844);
        int actualMatches=0,wrongMatches=0;
        for(var start:observedStarts) {
            var actual=RandomSpreadMath.potentialFromSourceChunk(
                    SEED,start.x(),start.z(),model);
            var fake=RandomSpreadMath.potentialFromSourceChunk(
                    SEED,start.x(),start.z(),wrongSalt);
            if(actual.x()==start.x() && actual.z()==start.z()) actualMatches++;
            if(fake.x()==start.x() && fake.z()==start.z()) wrongMatches++;
        }
        if(actualMatches!=4 || wrongMatches!=0) {
            throw new AssertionError("受控真值与随机结构模型不匹配，不能报告Seed识别");
        }

        // 一个控制结构由 CLIENT可见完整轮廓和独立真NBT建立校准；
        // 余下3个不同Region是实际客户端轮廓的2 FIT + 1 HOLDOUT。
        var reference=observedStarts.getFirst();
        var calibrated=StructureFootprintInverter.Calibration.fromControlled(
                "p35_posthoc_control_"+version.replace(".","_"),
                version,DIM,SET,TYPE,"controlled_reference",
                captured.getFirst().discoveredFootprint(),reference.x(),reference.z(),0);
        var constraints=new ArrayList<StartPlacementConstraint>();
        for(int i=1;i<4;i++) {
            var c=captured.get(i);
            var geometry=c.inferCalibratedHypotheses(List.of(calibrated));
            var truth=observedStarts.get(i);
            if(geometry.possibleChunkCount()!=1
                    || !geometry.alternatives().getFirst().contains(truth.x(),truth.z())) {
                throw new AssertionError("客户端完整轮廓丢失了独立 StructureStart："
                        + "样本 "+i+" candidates="+geometry.possibleChunkCount());
            }
            var observed=new StructureFootprintInverter.ObservedFootprint(
                    c.scope().worldFingerprint(),version,DIM,SET,TYPE,c.discoveredFootprint(),
                    StructureFootprintInverter.FootprintQuality.COMPLETE_PERIMETER);
            constraints.add(geometry.toConstraint(observed,
                    i<3?StartPlacementConstraint.Split.FIT
                            :StartPlacementConstraint.Split.HOLDOUT));
        }
        var input=new SolverResearchInput(Map.of(SET,model),constraints,
                new CandidateSeedSearch.SearchWindow(0,4096,4096,16,60_000));
        var result=LocalSmtSeedSolver.search(input,
                new SymbolicRandomSpread.Options(3,90_000,64),"z3",12);
        if(result.candidates().stream().noneMatch(x -> x.seedLow48()==SEED
                && x.fitMatches()==2 && x.holdoutMatches()==1)) {
            throw new AssertionError("真实客户端几何未通过Z3/Java 2FIT+1HOLDOUT");
        }
        if(result.rejectedByExactModel()!=0) {
            throw new AssertionError("真实客户端Z3候选未经原始Java RNG复核");
        }
        var wrong=CandidateSeedSearch.search(Map.of(SET,model),constraints,
                new CandidateSeedSearch.SearchWindow(2732,2733,1,1,20_000),
                ()->false);
        if(!wrong.candidates().isEmpty()) {
            throw new AssertionError("错误结构low48种子不应通过实际客户端轮廓");
        }
        System.out.println("P3.5 TRUE Fabric GUI "+version
                + " CLIENT_OBSERVED four real structure perimeters: "
                + "4/4 independently discovered 21x21 footprints, multiple orientations, true NBT start inside each "
                + "exact sparse candidate, 1 calibration + 2 FIT + 1 HOLDOUT; "
                + "Z3+Java low48=2731, wrong seed/salt rejected.");
        System.out.println("限定：客户端取证只证明可见外缘；实验的RCON仅用于导航，"
                + "服务端StructureStart只用于posthoc独立校准/验证；自然来源UNKNOWN，"
                + "没有破解任何未知自定义服务器或完整64位世界Seed。");
    }
}
