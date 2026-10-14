package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.compatibility.P34WorldgenCompatibilityAudit;
import io.github.fxjcangku.worldseedlab.core.compatibility.P34WorldgenCompatibilityAudit.*;
import io.github.fxjcangku.worldseedlab.core.solver.ClientQuartEvidenceArchive;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.VanillaOverworldBiomeModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * P3.4 客户端实际通过协议连接自建自定义地形服务器，在聊天框手动五次取证、
 * 一次导出所得 CLIENT_OBSERVED 逐条与独立存档FULL Anvil对照。
 *
 * <p>此测试不把受控服务端NBT作为客户端来源，不声明已取得用户未知私服Seed。
 * Seed=2731仅是公开受控环境用于原版MultiNoise错误假设的负例。
 */
public final class P34RealFabricCustomClientTest {
    private static final long CONTROL_SEED = 2731L;

    private P34RealFabricCustomClientTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || (!"26.1.2".equals(args[1]) && !"26.2".equals(args[1]))) {
            throw new IllegalArgumentException("用法: 真实客户端受控实验目录 Minecraft版本");
        }
        Path root = Path.of(args[0]);
        String version = args[1];
        var client = ClientQuartEvidenceArchive.read(root.resolve("controlled-client-quart.csv"));
        require(version.equals(client.scope().gameVersion())
                && "minecraft:overworld".equals(client.scope().dimension())
                && ClientQuartEvidenceArchive.UNVERIFIED_PROFILE.equals(
                        client.scope().generatorProfile())
                && client.samples().size() == 5, "真正客户端必须提供原始UNKNOWN生成器的5条quart");

        List<String> server = Files.readAllLines(root.resolve("server-full-quart.csv"));
        require(server.size() == 6
                && "quart_x,quart_y,quart_z,biome_id".equals(server.getFirst()),
                "独立自定义服务器FULL区块群系真值应有5处");
        for (int i = 0; i < 5; i++) {
            String[] t = server.get(i + 1).split(",", -1);
            var observed = client.samples().get(i);
            require(t.length == 4
                    && observed.quartX() == Integer.parseInt(t[0])
                    && observed.quartY() == Integer.parseInt(t[1])
                    && observed.quartZ() == Integer.parseInt(t[2])
                    && observed.observedBiome().equals(t[3])
                    && "minecraft:desert".equals(observed.observedBiome()),
                    "自定义平坦服务器真实客户端quart和同一真实FULL区块群系不一致: " + i);
            require(observed.origin() == Full64BiomeCandidateSearch.Origin.CLIENT_OBSERVED
                    && observed.split() == (i < 3
                    ? Full64BiomeCandidateSearch.Split.FIT
                    : Full64BiomeCandidateSearch.Split.HOLDOUT),
                    "服务端存档真值不得冒充客户端来源，FIT/HOLDOUT必须独立");
        }
        var scope = new Scope(client.scope().worldFingerprint(), version, "minecraft:overworld");
        var unknown = P34WorldgenCompatibilityAudit.biomes(scope, client, null,
                CONTROL_SEED, BiomeKnowledge.UNKNOWN_OR_CUSTOM,
                ChunkHistory.CONFIRMED_SINGLE_EPOCH);
        require(unknown.verdict() == Verdict.GENERATOR_UNKNOWN,
                "普通客户端不能依据可见群系自行确认自定义生成器");
        var modelScope = new Full64BiomeCandidateSearch.Scope(
                client.scope().worldFingerprint(), version, "minecraft:overworld",
                VanillaOverworldBiomeModel.GENERATOR_PROFILE);
        var vanilla = VanillaOverworldBiomeModel.create(modelScope);
        var counterexample = P34WorldgenCompatibilityAudit.biomes(
                scope, client, vanilla, CONTROL_SEED,
                BiomeKnowledge.EXPLICIT_VANILLA_HYPOTHESIS,
                ChunkHistory.CONFIRMED_SINGLE_EPOCH);
        require(counterexample.verdict() == Verdict.MODEL_CONFLICT
                && counterexample.mismatches() > 0,
                "真实自定义群系必须否定被强套的原版MultiNoise模型，而不是否定Seed本身");

        System.out.println("WorldSeedLab P3.4 REAL Fabric custom-world GUI " + version
                + ": 5/5 real CLIENT_OBSERVED samples independently match"
                + " saved FULL Anvil desert quart; 3 FIT + 2 HOLDOUT;"
                + " explicit vanilla MultiNoise hypothesis conflicts,"
                + " unknown custom generator remains unknown.");
        System.out.println("限定：完全隔离的公开受控自定义服务端，server FULL NBT仅用于测试对照；"
                + "非目标多人服，未通过客户端恢复结构low48或完整64位Seed。");
    }

    private static void require(boolean value, String explanation) {
        if (!value) throw new AssertionError(explanation);
    }
}
