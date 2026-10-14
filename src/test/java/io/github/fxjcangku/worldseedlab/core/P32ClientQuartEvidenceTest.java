package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.ClientQuartEvidenceArchive;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import java.nio.file.Files;
import java.nio.file.Path;

/** P3.2 仅验证普通客户端手动 quart 证据的数据、隐私作用域和失败保护。 */
public final class P32ClientQuartEvidenceTest {
    private P32ClientQuartEvidenceTest() {}

    public static void main(String[] args) throws Exception {
        for (String version : new String[] {"26.1.2", "26.2"}) {
            var session = new ClientQuartEvidenceArchive.Session(
                    "a".repeat(64), version, "minecraft:overworld");
            expectFail(session::snapshot, "样本不足不能导出");
            session.addObservedBlock(-4096, 72, 2048, "minecraft:desert");
            expectFail(() -> session.addObservedBlock(-4092, 100, 2052, "minecraft:desert"),
                    "改变高度不能使重复区域成为独立样本");
            session.addObservedBlock(-2048, 72, 4096, "minecraft:plains");
            session.addObservedBlock(0, 72, 0, "minecraft:forest");
            session.addObservedBlock(2048, 72, -4096, "minecraft:badlands");
            session.addObservedBlock(4096, 72, 2048, "minecraft:sparse_jungle");
            check(session.ready(), "必须明确有三FIT+两HOLDOUT");
            var captured = session.snapshot();
            check(captured.scope().generatorProfile().equals(
                            ClientQuartEvidenceArchive.UNVERIFIED_PROFILE),
                    "普通客户端不能自动冒充原版生成器");
            check(captured.samples().getFirst().quartX() == -1024,
                    "quart 坐标在负半轴应按照数学 floorDiv");
            check(captured.samples().get(3).split()
                            == Full64BiomeCandidateSearch.Split.HOLDOUT,
                    "第4条之后的样本只能作独立留出");
            String encoded = ClientQuartEvidenceArchive.encode(captured);
            var reconstructed = ClientQuartEvidenceArchive.decode(encoded);
            check(reconstructed.equals(captured), "证据落盘/恢复必须保持无损");
            check(encoded.contains("provenance=CLIENT_OBSERVED")
                            && encoded.contains("sampling=MANUAL_ALREADY_LOADED_FULL"),
                    "不能丢失采样来源和手动授权身份");
            check(reconstructed.forVanillaHypothesis().stream().allMatch(item ->
                            item.scope().generatorProfile().equals("minecraft:vanilla_overworld")
                            && item.origin() == Full64BiomeCandidateSearch.Origin.CLIENT_OBSERVED),
                    "离线声明原版假设不应改写来源类型");
            check(reconstructed.scope().generatorProfile().equals(
                            ClientQuartEvidenceArchive.UNVERIFIED_PROFILE),
                    "本机研究模型选择不得改写原始客户端证据");

            Path folder = Files.createTempDirectory("wsl-p32-quart-test-");
            try {
                Path file = folder.resolve("manual-quart.csv");
                Files.writeString(file, encoded);
                check(ClientQuartEvidenceArchive.read(file).equals(captured),
                        "必须从本机文件严格加载取证数据");
                expectFail(() -> ClientQuartEvidenceArchive.decode(encoded.replace(
                        "provenance=CLIENT_OBSERVED", "provenance=CONTROL_WORLD_OBSERVED")),
                        "服务端存档不能假冒普通客户端取证");
                expectFail(() -> ClientQuartEvidenceArchive.decode(encoded.replace(
                        "generatorProfile=worldseedlab:unknown_worldgen",
                        "generatorProfile=minecraft:vanilla_overworld")),
                        "客户端不能自行声明已验证原版生成器");
                expectFail(() -> ClientQuartEvidenceArchive.decode(encoded.replace(
                        "dimension=minecraft:overworld", "dimension=minecraft:the_nether")),
                        "重新导入也不能隐式改变世界作用域");
                expectFail(() -> ClientQuartEvidenceArchive.decode(encoded.replace(
                        "a".repeat(64), "not_a_fingerprint")), "HMAC 世界指纹非法应拒绝");
                String rows = encoded.replace("quart_x,quart_y,quart_z,biome_id,split\n",
                        "quart_x,quart_y,quart_z,biome_id,split\n"
                                + "-1024,18,512,minecraft:desert,FIT\n");
                expectFail(() -> ClientQuartEvidenceArchive.decode(rows),
                        "恶意重复quart点必须拒绝");
                String fake = encoded.replace(
                        "512,18,1024,minecraft:plains,FIT",
                        "512,18,1024,minecraft:plains,HOLDOUT");
                expectFail(() -> ClientQuartEvidenceArchive.decode(fake),
                        "不可把训练/留出角色互换伪造独立验证");
                Path symbolic = folder.resolve("symlink.csv");
                Files.createSymbolicLink(symbolic, file);
                try {
                    ClientQuartEvidenceArchive.read(symbolic);
                    throw new AssertionError("软链接输入必须拒绝");
                } catch (java.io.IOException expected) {
                    // 显式拒绝未经允许的文件代理访问。
                }
            } finally {
                try (var files = Files.walk(folder)) {
                    files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> {
                        try { Files.deleteIfExists(f); }
                        catch (java.io.IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    });
                }
            }
            session.clear();
            check(session.size() == 0, "显式清理应不保留旧会话数据");
        }
        System.out.println("WorldSeedLab P3.2 client quart archive/manual FIT-HOLDOUT, "
                + "world-scope isolation and fail-closed negatives passed");
    }

    private static void check(boolean actual, String description) {
        if (!actual) throw new AssertionError(description);
    }

    private static void expectFail(Runnable block, String description) {
        try { block.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(description);
    }
}
