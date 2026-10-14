package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.compatibility.StructureObservation.ChunkBounds;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 真正的 Minecraft 服务端已知 Seed 世界的结构起点对照，不是合成建筑。
 *
 * <p>必须先由 scripts/run_known_seed_server.py 启动真实 Minecraft、定位结构、
 * 强制生成对应区块并从本机 Anvil 存档中读取已完成的 StructureStart NBT。
 * 才能把这里的精确起点称为受控世界参考；禁止拿 /locate 的建筑显示坐标代替。
 *
 * <p>这是同版本真实世界生成 + SMT 的系统集成测试，不是普通客户端远端取种子，
 * 不证明服务端高16位或全部自定义服务器的生成规律可恢复。
 */
public final class P29RealWorldStructureTest {
    private static final long CONTROL_SEED = 2731L;
    private static final String WORLD = "e".repeat(64);
    private static final String DIMENSION = "minecraft:overworld";
    private static final String SET_ID = "minecraft:desert_pyramids";

    private P29RealWorldStructureTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "需要来自真实 Minecraft 存档的 structure starts CSV 与版本号");
        }
        String version = args[1];
        if (!"26.1.2".equals(version) && !"26.2".equals(version)) {
            throw new IllegalArgumentException("unsupported Minecraft version");
        }
        Path csv = Path.of(args[0]);
        List<String> lines = Files.readAllLines(csv);
        check(!lines.isEmpty() && lines.getFirst().equals("chunk_x,chunk_z"),
                "input must be generated from actual saved StructureStart NBT");
        List<RandomSpreadMath.Chunk> starts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] parts = line.split(",", -1);
            check(parts.length == 2, "invalid real-world structure start line");
            int x = Integer.parseInt(parts[0].trim());
            int z = Integer.parseInt(parts[1].trim());
            check(seen.add(x + ":" + z), "real-world start duplicated");
            starts.add(new RandomSpreadMath.Chunk(x, z));
        }
        check(starts.size() >= 3,
                "need at least three distinct truly generated StructureStart chunks");
        // 此集合的 Placement 参数取自受控世界所使用的原版规则假设；
        // 必须与真实世界生成的起点逐项对照，不能仅信任公式本身。
        var spec = new RandomSpreadSpec(SET_ID, 32, 8,
                RandomSpreadSpec.Distribution.LINEAR, 14357617);
        for (RandomSpreadMath.Chunk trueStart : starts) {
            RandomSpreadMath.Chunk potential = RandomSpreadMath.potentialFromSourceChunk(
                    CONTROL_SEED, trueStart.x(), trueStart.z(), spec);
            check(potential.equals(trueStart),
                    "actual generated StructureStart contradicts original RandomSpread: "
                    + trueStart + " potential=" + potential);
            check(RandomSpreadMath.potentialFromSourceChunk(
                    CONTROL_SEED + (1L << 48), trueStart.x(), trueStart.z(), spec)
                            .equals(trueStart),
                    "Legacy structure starts alone cannot distinguish upper16 seed bits");
        }

        List<StartPlacementConstraint> constraints = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var s = starts.get(i);
            constraints.add(StartPlacementConstraint.controlledExact(
                    WORLD, version, DIMENSION, SET_ID, s.x(), s.z(),
                    i < 2 ? StartPlacementConstraint.Split.FIT
                            : StartPlacementConstraint.Split.HOLDOUT));
        }
        var window = new CandidateSeedSearch.SearchWindow(
                0, 4096, 4096, 16, 60_000);
        var input = new SolverResearchInput(Map.of(SET_ID, spec), constraints, window);
        var inverse = LocalSmtSeedSolver.search(input,
                new SymbolicRandomSpread.Options(3, 90_000, 64), "z3", 12);
        check(inverse.candidates().stream().anyMatch(candidate ->
                        candidate.seedLow48() == CONTROL_SEED
                                && candidate.fitMatches() == 2
                                && candidate.holdoutMatches() == 1),
                "real Minecraft StructureStart should invert to known low48 seed");
        check(inverse.rejectedByExactModel() == 0,
                "real-world SMT solutions must match exact original Java RNG");
        var wrong = CandidateSeedSearch.search(
                Map.of(SET_ID, spec), constraints,
                new CandidateSeedSearch.SearchWindow(CONTROL_SEED + 1,
                        CONTROL_SEED + 2, 1, 1, 20_000),
                () -> false);
        check(wrong.candidates().isEmpty(),
                "known wrong seed must be rejected by real StructureStart evidence");
        // 使用真实生成并序列化的 StructurePiece.BB 作为受控几何测量。
        // 此处仍有服务端 NBT 权限，不能冒充普通客户端已自动识别的外轮廓。
        List<RealPieceGeometry> generatedGeometry = readActualGeometry(
                csv.resolveSibling("real_structure_geometry.csv"));
        check(generatedGeometry.size() == starts.size(),
                "each true StructureStart must have one complete generated-piece geometry record");
        check(generatedGeometry.stream().map(RealPieceGeometry::orientation)
                        .distinct().count() >= 2,
                "controlled world must include more than one genuinely generated orientation");
        for (RealPieceGeometry geometry : generatedGeometry) {
            check(starts.contains(new RandomSpreadMath.Chunk(
                            geometry.chunkX(), geometry.chunkZ())),
                    "geometry record cannot introduce a synthetic start");
        }
        check(generatedGeometry.size() >= 4,
                "one reference + two FIT + one HOLDOUT geometry requires four true structures");
        RealPieceGeometry control = generatedGeometry.getFirst();
        var calibration = StructureFootprintInverter.Calibration.fromControlled(
                "real_vanilla_structure_start_nbt_2731",
                version, DIMENSION, SET_ID, "minecraft:desert_pyramid",
                "verified_piece_bounds", control.blocks(),
                control.chunkX(), control.chunkZ(), 0);
        List<StartPlacementConstraint> geometricConstraints = new ArrayList<>();
        for (int i = 1; i < 4; i++) {
            RealPieceGeometry actual = generatedGeometry.get(i);
            var observed = new StructureFootprintInverter.ObservedFootprint(
                    WORLD, version, DIMENSION, SET_ID, "minecraft:desert_pyramid",
                    actual.blocks(),
                    StructureFootprintInverter.FootprintQuality.COMPLETE_PERIMETER);
            var inferred = StructureFootprintInverter.infer(observed, List.of(calibration));
            check(inferred.possibleChunkCount() == 1
                            && inferred.alternatives().equals(List.of(new ChunkBounds(
                                actual.chunkX(), actual.chunkZ(),
                                actual.chunkX(), actual.chunkZ()))),
                    "controlled Minecraft structure piece geometry must infer exact true start");
            geometricConstraints.add(inferred.toConstraint(observed,
                    i < 3 ? StartPlacementConstraint.Split.FIT
                            : StartPlacementConstraint.Split.HOLDOUT));
        }
        var geometricInput = new SolverResearchInput(
                Map.of(SET_ID, spec), geometricConstraints, window);
        var geometricInverse = LocalSmtSeedSolver.search(geometricInput,
                new SymbolicRandomSpread.Options(3, 90_000, 64), "z3", 12);
        check(geometricInverse.candidates().stream().anyMatch(candidate ->
                        candidate.seedLow48() == CONTROL_SEED
                                && candidate.fitMatches() == 2
                                && candidate.holdoutMatches() == 1),
                "actual game-generated piece geometry must invert back to known low48 seed");
        check(geometricInverse.rejectedByExactModel() == 0,
                "geometrically inferred low48 must pass independent exact Java verification");
        System.out.println("WorldSeedLab P2.9 REAL controlled piece geometry " + version
                + ": actual NBT calibration -> three separate regions -> "
                + "SMT low48=0x" + RandomSpreadMath.low48Hex(CONTROL_SEED)
                + " FIT=2 HOLDOUT=1; multiple orientations observed.");

        System.out.println("WorldSeedLab P2.9 REAL Minecraft " + version
                + " StructureStart NBT confirmed: " + starts.size()
                + " genuine starts; low48=0x" + RandomSpreadMath.low48Hex(CONTROL_SEED)
                + " recovered through real Z3 FIT=2 HOLDOUT=1; wrong seed rejected.");
        System.out.println("测试边界：受控已知种子单机服务端生成真值，非客户端建筑轮廓、"
                + "非未知服务器后台Seed或矿点推算。");
    }

    private record RealPieceGeometry(
            int chunkX, int chunkZ, StructureFootprintInverter.BlockBounds blocks,
            int orientation
    ) {}

    private static List<RealPieceGeometry> readActualGeometry(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file);
        check(!lines.isEmpty()
                        && lines.getFirst().equals(
                            "chunk_x,chunk_z,min_x,min_z,max_x,max_z,orientation"),
                "missing controlled-world serialized structure-piece geometry");
        List<RealPieceGeometry> parsed = new ArrayList<>();
        Set<String> locations = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] v = line.split(",", -1);
            check(v.length == 7, "invalid saved structure-piece geometry row");
            int chunkX = Integer.parseInt(v[0].trim());
            int chunkZ = Integer.parseInt(v[1].trim());
            check(locations.add(chunkX + ":" + chunkZ),
                    "duplicate real structure-piece geometry");
            parsed.add(new RealPieceGeometry(chunkX, chunkZ,
                    new StructureFootprintInverter.BlockBounds(
                            Integer.parseInt(v[2].trim()),
                            Integer.parseInt(v[3].trim()),
                            Integer.parseInt(v[4].trim()),
                            Integer.parseInt(v[5].trim())),
                    Integer.parseInt(v[6].trim())));
        }
        return List.copyOf(parsed);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
