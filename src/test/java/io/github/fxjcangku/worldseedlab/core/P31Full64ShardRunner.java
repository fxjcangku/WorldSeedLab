package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.Full64High16ShardPlan;
import io.github.fxjcangku.worldseedlab.core.solver.LocalSmtSeedSolver;
import io.github.fxjcangku.worldseedlab.core.solver.VanillaOverworldBiomeModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P3.1 原版世界生成器完整高16位分片验收入口。
 *
 * <p>唯一证据来源是 P3.0 中真实游戏已完成 FULL 区块保存的 quart 样本，
 * 并通过文件 SHA-256 锁定；不是数学模型自己生成的期望值。
 * low48=2731 已由 P2.9 实际 StructureStart 的 Z3 FIT/HOLDOUT 证实。
 *
 * <p>该入口不会读取服务器账号、level.dat 或远程地址，也不会主动连接 Minecraft。
 * 唯有外部汇总器确认所有分片独立成功，才能声称该已知 low48 的全部
 * 65536 个 high16 位在指定原版模型和观测下得到检查。
 */
public final class P31Full64ShardRunner {
    private static final String CONTROL_HASH =
            "73b8e9915d422b9632c4d411d0dcd6f0f2150e920d1a1d18630b07fb5e4e9602";
    private static final String WORLD = "e".repeat(64);
    private static final long CONTROL_LOW48 = 2731L;

    private P31Full64ShardRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "用法: shardIndex shardCount actualBiomesCsv outputReport");
        }
        int index = Integer.parseInt(args[0]);
        int count = Integer.parseInt(args[1]);
        Full64High16ShardPlan.Partition shard =
                Full64High16ShardPlan.partition(1, index, count);
        String version = System.getProperty("mcVersion", "26.2");
        if (!"26.1.2".equals(version) && !"26.2".equals(version)) {
            throw new IllegalArgumentException("不支持的 Minecraft 版本");
        }
        Path input = Path.of(args[2]);
        if (!Files.isRegularFile(input) || Files.isSymbolicLink(input)
                || Files.size(input) > 8192) {
            throw new IllegalArgumentException("缺少受控存档样本或样本文件不安全");
        }
        byte[] raw = Files.readAllBytes(input);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        if (!CONTROL_HASH.equals(digest)) {
            throw new IllegalArgumentException("真实 Minecraft 存档群系证据 SHA-256 不一致");
        }

        Full64BiomeCandidateSearch.Scope scope =
                new Full64BiomeCandidateSearch.Scope(
                        WORLD, version, "minecraft:overworld",
                        VanillaOverworldBiomeModel.GENERATOR_PROFILE);
        List<Full64BiomeCandidateSearch.BiomeSample> observations =
                readControlledSamples(input, scope);
        var model = VanillaOverworldBiomeModel.create(scope);

        long beginNanos = System.nanoTime();
        var result = Full64BiomeCandidateSearch.search(
                List.of(new LocalSmtSeedSolver.VerifiedLow48(CONTROL_LOW48, 2, 1)),
                observations, model,
                shard.budget(120_000, 128),
                () -> Thread.currentThread().isInterrupted());
        long millis = (System.nanoTime() - beginNanos) / 1_000_000L;
        if (result.completion()
                        != Full64BiomeCandidateSearch.Completion.REQUESTED_WINDOW_COMPLETE
                || result.evaluations() != shard.length()
                || result.nextIndex() != shard.endExclusive()
                || result.domainSize() != shard.domainSize()) {
            throw new AssertionError("分片尚未完整搜索，严禁输出 FULL_DOMAIN_CHECKED: "
                    + result.completion() + " evaluated=" + result.evaluations()
                    + " expected=" + shard.length() + " next=" + result.nextIndex());
        }

        boolean containsKnown = shard.fromIndex() <= 0 && 0 < shard.endExclusive();
        boolean knownRecovered = result.candidates().stream().anyMatch(
                candidate -> candidate.fullSeedBits() == CONTROL_LOW48
                        && candidate.fitMatches() == 3
                        && candidate.holdoutMatches() == 2);
        if (containsKnown != knownRecovered) {
            throw new AssertionError("已知 Seed=2731 正例不在正确分片或丢失");
        }
        Path target = Path.of(args[3]).normalize();
        Path expectedFolder = Path.of("build", "p31-high16-shards", version).normalize();
        if (!target.startsWith(expectedFolder) || !target.toString().endsWith(".properties")) {
            throw new IllegalArgumentException("实验输出必须位于受限 CI 分片目录");
        }
        Files.createDirectories(target.getParent());
        StringBuilder resultText = new StringBuilder(1024);
        resultText.append("schema=1\n")
                .append("kind=REAL_VANILLA_FULL64_HIGH16_EXHAUSTIVE_SHARD\n")
                .append("gameVersion=").append(version).append('\n')
                .append("knownLow48=").append(CONTROL_LOW48).append('\n')
                .append("biomeSource=").append(VanillaOverworldBiomeModel.GENERATOR_PROFILE)
                .append('\n')
                .append("inputSha256=").append(digest).append('\n')
                .append("biomeSamples=").append(observations.size()).append('\n')
                .append("fitSamples=3\nholdoutSamples=2\n")
                .append("shardIndex=").append(index).append('\n')
                .append("shardCount=").append(count).append('\n')
                .append("fromIndex=").append(shard.fromIndex()).append('\n')
                .append("endExclusive=").append(shard.endExclusive()).append('\n')
                .append("domainSize=").append(shard.domainSize()).append('\n')
                .append("completion=").append(result.completion()).append('\n')
                .append("evaluations=").append(result.evaluations()).append('\n')
                .append("nextIndex=").append(result.nextIndex()).append('\n')
                .append("holdoutRejected=").append(result.holdoutRejected()).append('\n')
                .append("elapsedMillis=").append(millis).append('\n')
                .append("candidateCount=").append(result.candidates().size()).append('\n');
        for (int i = 0; i < result.candidates().size(); i++) {
            var candidate = result.candidates().get(i);
            resultText.append("candidate.").append(i).append(".hex16=")
                    .append(candidate.hex16()).append('\n');
            resultText.append("candidate.").append(i).append(".fit=")
                    .append(candidate.fitMatches()).append('\n');
            resultText.append("candidate.").append(i).append(".holdout=")
                    .append(candidate.holdoutMatches()).append('\n');
        }
        Path pending = Files.createTempFile(target.getParent(), ".p31-shard-", ".tmp");
        try {
            Files.writeString(pending, resultText.toString());
            Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(pending);
        }
        System.out.println("WorldSeedLab P3.1 REAL Vanilla full64 high16 shard passed: "
                + version + " " + (index + 1) + "/" + count
                + " range=[" + shard.fromIndex() + "," + shard.endExclusive() + ")"
                + " evaluations=" + result.evaluations()
                + " resultCount=" + result.candidates().size()
                + " elapsedMillis=" + millis
                + " SHA256=" + digest);
    }

    public static List<Full64BiomeCandidateSearch.BiomeSample> readControlledSamples(
            Path csv, Full64BiomeCandidateSearch.Scope scope
    ) throws Exception {
        List<String> lines = Files.readAllLines(csv);
        if (lines.size() != 6
                || !lines.getFirst().equals("quart_x,quart_y,quart_z,biome_id")) {
            throw new IllegalArgumentException("固定受控世界须精确包含5条实际 quart 群系观测");
        }
        List<Full64BiomeCandidateSearch.BiomeSample> samples = new ArrayList<>();
        Set<String> positions = new HashSet<>();
        Set<String> kinds = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] row = line.split(",", -1);
            if (row.length != 4) throw new IllegalArgumentException("无效群系证据列数");
            int x = Integer.parseInt(row[0]), y = Integer.parseInt(row[1]);
            int z = Integer.parseInt(row[2]);
            String biome = row[3];
            if (!positions.add(x + ":" + y + ":" + z)) {
                throw new IllegalArgumentException("重复 quart 不能充当独立群系证据");
            }
            for (var item : samples) {
                long dx = (long) x - item.quartX();
                long dz = (long) z - item.quartZ();
                if (dx * dx + dz * dz < 256L * 256L) {
                    throw new IllegalArgumentException("群系观测空间相关性过高");
                }
            }
            samples.add(new Full64BiomeCandidateSearch.BiomeSample(
                    scope, x, y, z, biome,
                    samples.size() < 3 ? Full64BiomeCandidateSearch.Split.FIT
                            : Full64BiomeCandidateSearch.Split.HOLDOUT,
                    Full64BiomeCandidateSearch.Origin.CONTROL_WORLD_OBSERVED));
            kinds.add(biome);
        }
        if (kinds.size() < 3) {
            throw new IllegalArgumentException("群系证据单一，不能假装具备足够独立约束");
        }
        return List.copyOf(samples);
    }
}
