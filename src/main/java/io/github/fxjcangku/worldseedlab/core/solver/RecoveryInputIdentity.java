package io.github.fxjcangku.worldseedlab.core.solver;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/**
 * 条件高16续算身份。版本前缀防止把旧P4.1不完整摘要当作新格式。
 *
 * <p>签名覆盖结构几何/模型/Salt、完整约束及角色、低48搜索域和候选资格，
 * 再覆盖真实quart、气候表和高16分片。原始HMAC/XYZ仅进入内存摘要，
 * 不出现在报告。单次预算和续算下标不改变搜索域，故不纳入身份。
 */
public final class RecoveryInputIdentity {
    public static final String TOKEN_PREFIX = "wsl-p44-v1:";
    public static final int SCHEMA = 1;

    private RecoveryInputIdentity() {}

    public static void requireCurrentToken(String token) {
        if (token == null || !token.matches("wsl-p44-v1:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("未知或旧版续算摘要；请从本分片起点重新运行");
        }
    }

    public static String sha256(String token) {
        requireCurrentToken(token);
        return token.substring(TOKEN_PREFIX.length());
    }

    public static String conditionalToken(
            SeedRecoveryService.Request request, SolverResearchInput input,
            LocalSmtSeedSolver.Outcome low, Full64High16ShardPlan.Partition partition
    ) throws IOException {
        var high = request.high16();
        if (high == null || high.mode()
                != SeedRecoveryService.Mode.EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS) {
            throw new IllegalArgumentException("只有显式自定义气候研究具备本格式续算身份");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Java运行时缺少SHA-256", unavailable);
        }
        try (var out = new DataOutputStream(new DigestOutputStream(
                OutputStream.nullOutputStream(), digest))) {
            text(out, TOKEN_PREFIX);
            text(out, request.mode().name());
            text(out, "SERVER_SEED_UNKNOWN;ORE_UNVERIFIED;NATURAL_ORIGIN_UNKNOWN;SALT_UNVERIFIED");
            text(out, ClientStructureEvidence.calibrationId(request.structures().version()));
            var observed = request.structures();
            text(out, observed.worldFingerprint());
            text(out, observed.version());
            text(out, observed.dimension());
            out.writeInt(observed.sites().size());
            // 全部完整客户端栅格也被绑定，不能替换未进入有限约束集的证据后静默续算。
            for (var site : observed.sites().stream().sorted(Comparator
                    .comparingInt(ClientStructureEvidence.Site::chunkX)
                    .thenComparingInt(ClientStructureEvidence.Site::chunkZ)).toList()) {
                text(out, site.raster().encode());
            }
            // 每Salt假设独立续算；同批其它Salt不参与本模型的候选索引。
            out.writeInt(input.models().size());
            for (String id : input.models().keySet().stream().sorted().toList()) {
                var model = input.models().get(id);
                text(out, id);
                text(out, model.structureSetId());
                out.writeInt(model.spacing());
                out.writeInt(model.separation());
                text(out, model.distribution().name());
                out.writeInt(model.salt());
            }
            out.writeInt(input.constraints().size());
            for (var constraint : input.constraints()) {
                text(out, constraint.worldFingerprint());
                text(out, constraint.gameVersion());
                text(out, constraint.dimension());
                text(out, constraint.structureSetId());
                text(out, constraint.origin().name());
                text(out, constraint.split().name());
                var bounds = constraint.possibleStartChunks();
                out.writeInt(bounds.minX());
                out.writeInt(bounds.minZ());
                out.writeInt(bounds.maxX());
                out.writeInt(bounds.maxZ());
                out.writeInt(constraint.alternativeStartChunks().size());
                for (var alternative : constraint.alternativeStartChunks()) {
                    out.writeInt(alternative.minX());
                    out.writeInt(alternative.minZ());
                    out.writeInt(alternative.maxX());
                    out.writeInt(alternative.maxZ());
                }
            }
            out.writeLong(input.searchWindow().startInclusive());
            out.writeLong(input.searchWindow().endExclusive());
            // 固定SMT策略属于本管线版本；更改展开上限必须改变输入身份。
            text(out, "SMT:maxRejectionDraws=3;maximumFitRegions=64;maximumModels=16");
            text(out, low.status().name());
            out.writeBoolean(low.incompleteRejectionPaths());
            out.writeInt(low.candidates().size());
            for (var candidate : low.candidates().stream().sorted(Comparator.comparingLong(
                    LocalSmtSeedSolver.VerifiedLow48::seedLow48)).toList()) {
                out.writeLong(candidate.seedLow48());
                out.writeInt(candidate.fitMatches());
                out.writeInt(candidate.holdoutMatches());
            }
            var archive = high.archive();
            text(out, archive.scope().worldFingerprint());
            text(out, archive.scope().gameVersion());
            text(out, archive.scope().dimension());
            text(out, archive.scope().generatorProfile());
            out.writeInt(archive.samples().size());
            for (var sample : archive.samples()) {
                out.writeInt(sample.quartX());
                out.writeInt(sample.quartY());
                out.writeInt(sample.quartZ());
                text(out, sample.observedBiome());
                text(out, sample.split().name());
                text(out, sample.origin().name());
            }
            var spec = high.customModel();
            text(out, "USER_SUPPLIED_UNVERIFIED");
            text(out, spec.generatorProfile());
            text(out, spec.noiseRouter());
            out.writeInt(spec.entries().size());
            // 参数表顺序影响等距气候候选的选择，必须保留原顺序。
            for (var entry : spec.entries()) {
                var point = entry.getFirst();
                text(out, entry.getSecond());
                for (var axis : java.util.List.of(point.temperature(), point.humidity(),
                        point.continentalness(), point.erosion(), point.depth(), point.weirdness())) {
                    out.writeLong(axis.min());
                    out.writeLong(axis.max());
                }
                out.writeLong(point.offset());
            }
            out.writeInt(spec.possibleBiomes().size());
            for (String biome : spec.possibleBiomes().stream().sorted().toList()) text(out, biome);
            out.writeInt(partition.shardIndex());
            out.writeInt(partition.shardCount());
            out.writeLong(partition.fromIndex());
            out.writeLong(partition.endExclusive());
            out.writeLong(partition.domainSize());
        }
        return TOKEN_PREFIX + HexFormat.of().formatHex(digest.digest());
    }

    /** 长度前缀避免字段拼接歧义，也允许受限栅格文本超过writeUTF的64KiB上限。 */
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
