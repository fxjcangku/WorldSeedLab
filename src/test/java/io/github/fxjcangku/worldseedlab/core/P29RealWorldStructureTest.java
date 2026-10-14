package io.github.fxjcangku.worldseedlab.core;

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
        System.out.println("WorldSeedLab P2.9 REAL Minecraft " + version
                + " StructureStart NBT confirmed: " + starts.size()
                + " genuine starts; low48=0x" + RandomSpreadMath.low48Hex(CONTROL_SEED)
                + " recovered through real Z3 FIT=2 HOLDOUT=1; wrong seed rejected.");
        System.out.println("测试边界：受控已知种子单机服务端生成真值，非客户端建筑轮廓、"
                + "非未知服务器后台Seed或矿点推算。");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
