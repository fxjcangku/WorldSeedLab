package io.github.fxjcangku.worldseedlab.core.solver;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * 恢复结果的文本和schema=1本机JSON视图，不对内部证据对象做通用反射序列化。
 *
 * <p>仅白名单字段进入报告：无HMAC、XYZ、输入路径或私钥。候选Seed仍属于
 * 本机研究数据；显式保存时仅创建新文件，绝不覆盖已有证据或跟随符号链接。
 */
public final class RecoveryReport {
    public static final String FORMAT = "WorldSeedLabRecovery";
    public static final int SCHEMA = 1;

    private RecoveryReport() {}

    /** 求解之前调用预检；最终写入仍使用CREATE_NEW关闭同名文件竞争窗口。 */
    public static void validateNewTarget(Path target) throws IOException {
        Objects.requireNonNull(target);
        Path absolute = target.toAbsolutePath().normalize();
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("报告目标已存在；请选择新的本机JSON文件");
        }
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(parent)) {
            throw new IOException("报告父目录必须已经存在且可写");
        }
        for (Path component = parent; component != null; component = component.getParent()) {
            if (Files.isSymbolicLink(component)) {
                throw new IOException("报告路径不得经过符号链接");
            }
        }
    }

    public static void writeNew(Path target, SeedRecoveryService.Result result) throws IOException {
        validateNewTarget(target);
        Files.writeString(target.toAbsolutePath().normalize(), json(result),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    public static String json(SeedRecoveryService.Result result) {
        Objects.requireNonNull(result);
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("schema", SCHEMA);
        root.addProperty("game_version", result.gameVersion());
        root.addProperty("dimension", result.dimension() == null ? "UNKNOWN" : result.dimension());
        root.addProperty("mode", result.mode().name());
        root.addProperty("result", result.status().name());
        root.addProperty("server_seed_status", result.serverSeedStatus());
        root.addProperty("ore_prediction_status", result.orePredictionStatus());
        root.addProperty("ready_for_mining", result.readyForMining());
        root.addProperty("natural_origin", "UNKNOWN");
        root.addProperty("server_salt", "UNVERIFIED");
        root.addProperty("generator_identity", "UNKNOWN");
        root.addProperty("generation_history", "UNKNOWN");
        root.addProperty("scope", "LOCAL_CONDITIONAL_RESEARCH_ONLY");
        var counts = result.evidence();
        var evidence = new JsonObject();
        evidence.addProperty("complete_footprint_files", counts.footprintFiles());
        evidence.addProperty("unique_footprints", counts.uniqueFootprints());
        evidence.addProperty("independent_regions", counts.independentRegions());
        evidence.addProperty("structure_fit", counts.structureFit());
        evidence.addProperty("structure_holdout", counts.structureHoldout());
        evidence.addProperty("quart_fit", counts.quartFit());
        evidence.addProperty("quart_holdout", counts.quartHoldout());
        root.add("evidence", evidence);
        var hypotheses = new JsonArray();
        for (var item : result.hypotheses()) {
            var entry = new JsonObject();
            var model = item.structureModel();
            var structure = new JsonObject();
            structure.addProperty("structure_set", model.structureSetId());
            structure.addProperty("spacing", model.spacing());
            structure.addProperty("separation", model.separation());
            structure.addProperty("distribution", model.distribution().name());
            structure.addProperty("salt", model.salt());
            structure.addProperty("calibration", ClientStructureEvidence.calibrationId(result.gameVersion()));
            structure.addProperty("qualification", "EXPLICIT_UNVERIFIED_HYPOTHESIS");
            entry.add("structure_model", structure);
            entry.add("low48", low48(item.low48()));
            entry.add("high16", high16(result.mode(), item.high16()));
            hypotheses.add(entry);
        }
        root.add("hypotheses", hypotheses);
        return new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
                .toJson(root) + "\n";
    }

    private static JsonObject low48(SeedRecoveryService.Low48Result result) {
        var out = new JsonObject();
        var outcome = result.outcome();
        out.addProperty("qualification", "CONDITIONAL_STRUCTURE_MODEL");
        out.addProperty("completion", outcome.status().name());
        out.addProperty("bounded_model_complete", result.boundedModelComplete());
        out.addProperty("full_domain_complete", result.fullDomainComplete());
        out.addProperty("incomplete_rejection_paths", outcome.incompleteRejectionPaths());
        out.addProperty("start_inclusive", result.window().startInclusive());
        out.addProperty("end_exclusive", result.window().endExclusive());
        out.addProperty("domain_size", CandidateSeedSearch.TOTAL_48);
        out.addProperty("solver_calls", outcome.solverCalls());
        out.addProperty("holdout_rejected", outcome.rejectedByHoldout());
        out.addProperty("exact_model_rejected", outcome.rejectedByExactModel());
        var candidates = new JsonArray();
        for (var item : outcome.candidates()) {
            var candidate = new JsonObject();
            candidate.addProperty("hex", item.low48Hex());
            candidate.addProperty("unsigned_decimal", Long.toString(item.seedLow48()));
            candidate.addProperty("fit_matches", item.fitMatches());
            candidate.addProperty("holdout_matches", item.holdoutMatches());
            candidates.add(candidate);
        }
        out.add("candidates", candidates);
        return out;
    }

    private static JsonObject high16(SeedRecoveryService.Mode mode,
                                     SeedRecoveryService.High16Result result) {
        var out = new JsonObject();
        var candidates = new JsonArray();
        if (result == null) {
            out.addProperty("completion", mode == SeedRecoveryService.Mode.STRUCTURE_LOW48
                    ? "NOT_REQUESTED" : "NO_LOW48_CANDIDATES");
            out.addProperty("full_domain_complete", false);
        } else {
            out.addProperty("qualification", result.mode()
                    == SeedRecoveryService.Mode.EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS
                    ? "CONDITIONAL_CUSTOM_FULL64" : "FULL64_MODEL_CANDIDATE");
            out.addProperty("completion", result.completion().name());
            out.addProperty("generator_profile", result.generatorProfile());
            out.addProperty("model_trust", result.modelTrust());
            out.addProperty("server_generator_verified", false);
            out.addProperty("requested_window_complete", result.requestedWindowComplete());
            out.addProperty("full_domain_complete", result.fullDomainComplete());
            out.addProperty("domain_size", result.domainSize());
            out.addProperty("requested_from_index", result.budget().fromIndexInclusive());
            out.addProperty("requested_to_index_exclusive", result.budget().toIndexExclusive());
            out.addProperty("next_index", result.nextIndex());
            out.addProperty("evaluations", result.evaluations());
            out.addProperty("fit_rejected", result.fitRejected());
            out.addProperty("holdout_rejected", result.holdoutRejected());
            out.addProperty("shard_index", result.partition().shardIndex());
            out.addProperty("shard_count", result.partition().shardCount());
            out.addProperty("shard_from_index", result.partition().fromIndex());
            out.addProperty("shard_to_index_exclusive", result.partition().endExclusive());
            if (!result.resumeToken().isEmpty()) {
                out.addProperty("input_digest_schema", RecoveryInputIdentity.SCHEMA);
                out.addProperty("input_sha256", RecoveryInputIdentity.sha256(result.resumeToken()));
                out.addProperty("resume_token", result.resumeToken());
            }
            for (var item : result.candidates()) {
                var candidate = new JsonObject();
                candidate.addProperty("hex", item.hex16());
                // Java long负数及大整数必须保留为十进制字符串，避免JS数字舍入。
                candidate.addProperty("signed_decimal", item.signedDecimal());
                candidate.addProperty("low48_hex", RandomSpreadMath.low48Hex(item.low48()));
                candidate.addProperty("high16", item.high16Unsigned());
                candidate.addProperty("fit_matches", item.fitMatches());
                candidate.addProperty("holdout_matches", item.holdoutMatches());
                candidates.add(candidate);
            }
        }
        out.add("candidates", candidates);
        return out;
    }

    /** 保留既有研究脚本识别的候选/计数标记，全部内容从同一类型化结果生成。 */
    public static String text(SeedRecoveryService.Result result) {
        var out = new StringBuilder(4096);
        var counts = result.evidence();
        line(out, "WorldSeedLab P4.4 · 真实客户端证据离线恢复管线");
        line(out, "游戏版本=" + result.gameVersion() + " | 完整轮廓文件=" + counts.footprintFiles()
                + " | 可用不重复轮廓=" + counts.uniqueFootprints()
                + " | 独立结构Region=" + counts.independentRegions());
        line(out, "来源=CLIENT_OBSERVED | 自然来源=UNKNOWN | 生成器=UNKNOWN | 结构Salt未验证");
        line(out, "RESULT=" + result.status());
        for (var hypothesis : result.hypotheses()) {
            var spec = hypothesis.structureModel();
            var low = hypothesis.low48();
            var outcome = low.outcome();
            line(out, "结构模型假设：spacing=" + spec.spacing() + " separation=" + spec.separation()
                    + " " + spec.distribution() + " Salt=" + spec.salt());
            line(out, "Z3低48状态=" + outcome.status() + " | 候选=" + outcome.candidates().size()
                    + " | 留出拒绝=" + outcome.rejectedByHoldout()
                    + " | Java精确拒绝=" + outcome.rejectedByExactModel());
            line(out, "本轮请求区间=" + low.window().startInclusive() + ".." + low.window().endExclusive()
                    + " | 非二幂拒绝路径完整性未证明=" + outcome.incompleteRejectionPaths()
                    + " | 能否声称全48位域穷尽=" + low.fullDomainComplete());
            if (!low.fullDomainComplete()) line(out, "RESEARCH_INCOMPLETE_OR_CONDITIONAL: "
                    + "受控窗口、有限拒绝路径和计算上限不证明完整世界Seed。");
            for (var candidate : outcome.candidates()) {
                line(out, "LOW48_MODEL_CANDIDATE=0x" + candidate.low48Hex()
                        + " | FIT=" + candidate.fitMatches() + " | HOLDOUT=" + candidate.holdoutMatches()
                        + " | NATURAL_ORIGIN=UNKNOWN");
            }
            var full = hypothesis.high16();
            if (full == null) continue;
            boolean custom = full.mode() == SeedRecoveryService.Mode.EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS;
            if (custom) {
                line(out, "P4.1_CUSTOM_MODEL_SOURCE=USER_SUPPLIED_UNVERIFIED"
                        + " | MODEL_NOISE_ROUTER=EXPLICIT_VANILLA_OVERWORLD_HYPOTHESIS");
                line(out, "P4.1_INPUT_SHA256=" + RecoveryInputIdentity.sha256(full.resumeToken()));
                line(out, "RECOVERY_RESUME_TOKEN=" + full.resumeToken());
                line(out, "分片=" + full.partition().shardIndex() + "/" + full.partition().shardCount()
                        + " | 分片区间=" + full.partition().fromIndex() + ".."
                        + full.partition().endExclusive());
            }
            line(out, (custom ? "P4.1_CONDITIONAL_RESULT=" : "FULL64_仅原版假设=") + full.completion()
                    + " | examined=" + full.evaluations() + " | fitRejected=" + full.fitRejected()
                    + " | holdoutRejected=" + full.holdoutRejected()
                    + " | NEXT_INDEX=" + full.nextIndex() + " | DOMAIN_SIZE=" + full.domainSize());
            for (var candidate : full.candidates()) {
                line(out, (custom ? "CONDITIONAL_CUSTOM_FULL64=0x" : "FULL64_MODEL_CANDIDATE=0x")
                        + candidate.hex16() + " | signed=" + candidate.signedDecimal()
                        + " | FIT=" + candidate.fitMatches() + " | HOLDOUT=" + candidate.holdoutMatches()
                        + " | SEED_NOT_VERIFIED_ON_REMOTE_SERVER");
            }
            if (!full.fullDomainComplete()) line(out, "DOMAIN_INCOMPLETE: "
                    + "本次没有检查全部条件高16域，旧分片未自动合并。");
        }
        if (result.status() == SeedRecoveryService.ResultStatus.INSUFFICIENT_GEOMETRY_EVIDENCE) {
            line(out, "LOW48=NOT_DERIVED；至少需要三个独立RandomSpread区域的完整轮廓。");
        }
        if (result.status() == SeedRecoveryService.ResultStatus.MODEL_REGISTRY_INCOMPATIBLE) {
            line(out, "所选群系模型无法表达客户端观测，未启动Z3或高16搜索。");
        }
        line(out, "FULL64=UNKNOWN_CUSTOM_WORLDGEN | SERVER_SEED_STATUS=UNKNOWN"
                + " | ORE_PREDICTION_STATUS=UNVERIFIED | READY_FOR_MINING=false");
        line(out, "模型一致仍不证明目标服务器真实Seed、生成器、结构Salt、天然来源或矿物规则。");
        return out.toString();
    }

    private static void line(StringBuilder out, String line) { out.append(line).append('\n'); }
}
