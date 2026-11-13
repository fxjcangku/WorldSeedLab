package io.github.fxjcangku.worldseedlab.core.solver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 普通客户端已存证据的显式离线入口：只负责参数、导入、调用和呈现。
 *
 * <p>证据审计在ClientStructureEvidence，恢复状态在SeedRecoveryService，
 * 文本/本机JSON由RecoveryReport产生。默认不落盘，不修改客户端原始证据，
 * 不将结构low48或条件full64转换为已认证服务器Seed/可挖矿资格。
 */
public final class RealClientSeedRecoveryCli {
    private RealClientSeedRecoveryCli() {}

    /** 两种结果域必须显式区分；未知Salt有效状态永远不是世界low48候选。 */
    public enum RecoveryMode {
        EXPLICIT_SALT_HYPOTHESIS,
        EFFECTIVE_STRUCTURE_STATE_ONLY;

        public static RecoveryMode parse(String value, boolean high16Requested) {
            RecoveryMode mode = RecoveryMode.valueOf(value);
            if (mode == EFFECTIVE_STRUCTURE_STATE_ONLY && high16Requested) {
                throw new IllegalArgumentException("未知Salt的E48不能接入任何原版或自定义高16位搜索");
            }
            return mode;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 5 || args.length == 3) {
            throw new IllegalArgumentException(
                    "用法：p39RealSeedRecovery -PrecoveryEvidence=observations.zip "
                    + "[-PrecoverySalts=14357617] [-PrecoveryReport=新的本机报告.json] "
                    + "[-PrecoveryQuart=client-quart.csv -PrecoveryBiomeMode="
                    + "EXPLICIT_VANILLA_OVERWORLD_HYPOTHESIS或EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS "
                    + "-PrecoveryCustomModel=climate.json]");
        }
        String version = System.getProperty("mcVersion", "26.2");
        RecoveryMode saltMode = RecoveryMode.parse(System.getProperty(
                "recoverySaltMode", "EXPLICIT_SALT_HYPOTHESIS"), args.length >= 4);
        boolean customMode = args.length == 5
                && SeedRecoveryService.Mode.EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS.name().equals(args[3]);
        if (!customMode) {
            for (String key : List.of("recoveryShardIndex", "recoveryShardCount", "recoveryHigh16Resume",
                    "recoveryHigh16Digest", "recoveryHigh16MaxEvaluations")) {
                if (System.getProperty(key) != null) {
                    throw new IllegalArgumentException("分片/续算参数仅适用于显式自定义气候模型");
                }
            }
        }
        List<Integer> salts = salts(args.length >= 2 ? args[1] : "14357617");
        long start = Long.parseLong(System.getProperty("recoveryStart", "0"));
        long end = Long.parseLong(System.getProperty("recoveryEnd",
                Long.toString(CandidateSeedSearch.TOTAL_48)));
        String reportArgument = System.getProperty("recoveryReport", "");
        Path report = reportArgument.isBlank() ? null : Path.of(reportArgument);
        if (saltMode == RecoveryMode.EFFECTIVE_STRUCTURE_STATE_ONLY && report != null) {
            throw new IllegalArgumentException("E48有效状态不能写入世界low48/完整Seed结果报告");
        }
        if (report != null) RecoveryReport.validateNewTarget(report);

        SeedRecoveryService.High16Request high = null;
        if (args.length >= 4) {
            var mode = SeedRecoveryService.Mode.valueOf(args[3]);
            var archive = ClientQuartEvidenceArchive.read(Path.of(args[2]));
            if (mode == SeedRecoveryService.Mode.EXPLICIT_VANILLA_OVERWORLD_HYPOTHESIS
                    && args.length == 4) {
                high = SeedRecoveryService.High16Request.vanilla(archive);
            } else if (mode == SeedRecoveryService.Mode.EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS
                    && args.length == 5) {
                var model = P40CustomClimateModel.read(Path.of(args[4]));
                int shard = Integer.parseInt(System.getProperty("recoveryShardIndex", "0"));
                int shards = Integer.parseInt(System.getProperty("recoveryShardCount", "16"));
                String resume = System.getProperty("recoveryHigh16Resume", "");
                String token = System.getProperty("recoveryHigh16Digest", "");
                long maximum = Long.parseLong(System.getProperty("recoveryHigh16MaxEvaluations", "128"));
                high = new SeedRecoveryService.High16Request(mode, archive, model,
                        shard, shards, resume.isBlank() ? null : Long.parseLong(resume), token, maximum);
            } else {
                throw new IllegalArgumentException("quart、高16生成器假设和参数文件必须成套明确提供");
            }
        }
        var observed = ClientStructureEvidence.inspect(Path.of(args[0]), version);
        if (saltMode == RecoveryMode.EFFECTIVE_STRUCTURE_STATE_ONLY) {
            // 与世界low48服务保持结果类型隔离，且仍复用同一份真实轮廓审计。
            new CandidateSeedSearch.SearchWindow(start, end, 1_000_000, 32, 90_000);
            if (observed.independentRegions(32) < 3) {
                System.out.println("RESULT=INSUFFICIENT_GEOMETRY_EVIDENCE");
                System.out.println("WORLD_LOW48=UNKNOWN | STRUCTURE_SALT=UNKNOWN | FULL64=UNKNOWN");
                return;
            }
            recoverEffectiveState(observed, start, end);
            return;
        }
        var request = new SeedRecoveryService.Request(observed, salts, start, end, high);
        var result = new SeedRecoveryService().recover(request);
        if (report != null) RecoveryReport.writeNew(report, result);
        System.out.print(RecoveryReport.text(result));
        if (report != null) System.out.println("REPORT=CREATED_LOCAL_SCHEMA_1");
    }

    private static void recoverEffectiveState(ClientStructureEvidence.Observation observed,
                                              long startInclusive, long endExclusive)
            throws IOException {
        var input = ClientStructureEvidence.constraints(observed, 0, startInclusive, endExclusive);
        System.out.println("RESULT_KIND=" + EffectiveStructureStateSearch.RESULT_KIND);
        System.out.println("结构模型假设：spacing=32 separation=8 LINEAR；"
                + "结构Salt=UNKNOWN；Salt归零仅用于求E48=(Seed+Salt) mod 2^48");
        var result = EffectiveStructureStateSearch.search(input,
                new SymbolicRandomSpread.Options(3, 90_000, 64), "z3", 16);
        System.out.println("E48_SOLVER_STATUS=" + result.status()
                + " | 候选=" + result.candidates().size()
                + " | 留出拒绝=" + result.rejectedByHoldout()
                + " | Java精确拒绝=" + result.rejectedByExactModel());
        System.out.println("E48_WINDOW=" + startInclusive + ".." + endExclusive
                + " | 非二幂拒绝路径完整性未证明=" + result.incompleteRejectionPaths());
        for (var candidate : result.candidates()) {
            System.out.println("EFFECTIVE_STRUCTURE_STATE_CANDIDATE=0x" + candidate.hex12()
                    + " | FIT=" + candidate.fitMatches()
                    + " | HOLDOUT=" + candidate.holdoutMatches()
                    + " | NATURAL_ORIGIN=UNKNOWN");
        }
        System.out.println("WORLD_LOW48=UNKNOWN | STRUCTURE_SALT=UNKNOWN | FULL64=UNKNOWN");
        System.out.println("E48仅约束单结构集合的Seed与Salt之和；不生成世界low48，"
                + "不运行高16位搜索，有限窗口/超时/模型上限不能表示全域完成。");
    }

    private static List<Integer> salts(String argument) {
        String[] tokens = argument.split(",", -1);
        if (tokens.length < 1 || tokens.length > 4) {
            throw new IllegalArgumentException("每次最多四个明确声明的结构Salt假设");
        }
        List<Integer> out = new ArrayList<>();
        for (String token : tokens) {
            int value = Integer.parseInt(token.strip());
            if (out.contains(value)) throw new IllegalArgumentException("不能重复相同Salt假设");
            out.add(value);
        }
        return List.copyOf(out);
    }
}
