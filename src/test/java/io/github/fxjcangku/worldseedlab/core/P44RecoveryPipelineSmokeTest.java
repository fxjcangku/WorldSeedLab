package io.github.fxjcangku.worldseedlab.core;

import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import io.github.fxjcangku.worldseedlab.core.solver.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.world.level.biome.Climate;

/**
 * P4.4管线安全契约：作用域必须在Z3前拒绝，Salt/模型身份防旧断点串用，
 * 每假设结果独立，报告不泄露坐标且永不提升挖矿资格。
 *
 * <p>沿用P3.9明确标记的合成栅格，并用可计数后端检查调度边界；不把这些
 * 测试说成真实客户端取证、真实Z3或未知服务器种子恢复。游戏/Z3另走既有E2E。
 */
public final class P44RecoveryPipelineSmokeTest {
    private static final String WORLD = "c".repeat(64);
    private static final int SALT = 14357617;

    private P44RecoveryPipelineSmokeTest() {}

    public static void main(String[] args) throws Exception {
        String version = System.getProperty("mcVersion", "26.2");
        Path root = Files.createTempDirectory("wsl-p44-pipeline-");
        try {
            Path empty = Files.createDirectory(root.resolve("empty"));
            AtomicInteger calls = new AtomicInteger();
            var service = new SeedRecoveryService(input -> {
                calls.incrementAndGet();
                return controlledOutcome(input, LocalSmtSeedSolver.Status.BOUNDED_MODEL_EXHAUSTED);
            });
            var noEvidence = service.recover(new SeedRecoveryService.Request(
                    ClientStructureEvidence.inspect(empty, version), List.of(SALT), 0, 4096, null));
            check(calls.get() == 0 && noEvidence.status()
                    == SeedRecoveryService.ResultStatus.INSUFFICIENT_GEOMETRY_EVIDENCE,
                    "缺少轮廓必须在后端之前返回证据不足");

            Path evidence = Files.createDirectory(root.resolve("evidence"));
            int[][] starts = {{-887, -879}, {-876, 875}, {22, 8}, {885, 871}};
            for (int i = 0; i < starts.length; i++) {
                P39RealSeedRecoverySmokeTest.writeSyntheticClientFootprint(
                        evidence.resolve("client-auto-desert-" + i + ".properties"),
                        starts[i][0], starts[i][1], WORLD, version);
            }
            var observed = ClientStructureEvidence.inspect(evidence, version);
            var batch = service.recover(new SeedRecoveryService.Request(
                    observed, List.of(SALT, SALT + 227), 0, 4096, null));
            check(calls.get() == 2 && batch.hypotheses().size() == 2,
                    "每个Salt必须分别调用后端并保留独立结果");
            check(batch.hypotheses().get(0).low48().outcome().candidates().getFirst().seedLow48() == 2731
                    && batch.hypotheses().get(1).low48().outcome().candidates().getFirst().seedLow48() == 2504,
                    "不同Salt下的条件低48不能合并成一个后台Seed");
            check(!batch.readyForMining() && batch.serverSeedStatus().equals("UNKNOWN")
                    && batch.orePredictionStatus().equals("UNVERIFIED"),
                    "类型化结果不得提供矿物预测/后台Seed认证");

            var badBackend = new SeedRecoveryService(input -> new LocalSmtSeedSolver.Outcome(
                    LocalSmtSeedSolver.Status.BOUNDED_MODEL_EXHAUSTED, 1, 0, 0,
                    List.of(new LocalSmtSeedSolver.VerifiedLow48(2732, 3, 1)), "受控负例", true));
            rejects(() -> badBackend.recover(new SeedRecoveryService.Request(
                    observed, List.of(SALT), 0, 4096, null)),
                    "后端伪造FIT/HOLDOUT计数也必须被精确Java规则拒绝");

            preflightGuards(service, calls, observed, version);
            String token = checkpointIdentity(observed, version);
            reportContract(root, evidence, batch, token);
            importerGuards(root, evidence, version);
            defaultCliIsReadOnly(empty);
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("P4.4 typed recovery pipeline, scope-before-backend, checkpoint identity, "
                + "per-Salt report and privacy contracts PASS: " + version
                + "（合成栅格/受控后端；真实游戏与Z3由独立E2E验收）");
    }

    private static void preflightGuards(SeedRecoveryService service, AtomicInteger calls,
            ClientStructureEvidence.Observation observed, String version) throws Exception {
        int before = calls.get();
        for (var wrong : List.of(
                archive("d".repeat(64), version, "minecraft:overworld", "minecraft:plains", 0),
                archive(WORLD, version.equals("26.2") ? "26.1.2" : "26.2",
                        "minecraft:overworld", "minecraft:plains", 0),
                archive(WORLD, version, "minecraft:the_nether", "minecraft:plains", 0))) {
            rejects(() -> service.recover(new SeedRecoveryService.Request(observed, List.of(SALT),
                    0, 4096, SeedRecoveryService.High16Request.vanilla(wrong))),
                    "跨世界/版本/维度quart必须在Z3前拒绝");
        }
        check(calls.get() == before, "跨作用域输入不允许启动任何结构后端");
        var customBiome = archive(WORLD, version, "minecraft:overworld", "example:custom", 0);
        var vanillaConflict = service.recover(new SeedRecoveryService.Request(observed, List.of(SALT),
                0, 4096, SeedRecoveryService.High16Request.vanilla(customBiome)));
        var customConflict = service.recover(new SeedRecoveryService.Request(observed, List.of(SALT),
                0, 4096, high(customBiome, specification("minecraft:plains"), 0, 16, null, "", 1)));
        check(vanillaConflict.status() == SeedRecoveryService.ResultStatus.MODEL_REGISTRY_INCOMPATIBLE
                        && customConflict.status() == SeedRecoveryService.ResultStatus.MODEL_REGISTRY_INCOMPATIBLE
                        && calls.get() == before,
                "模型不能表达观测群系时不得先浪费Z3预算");
        var archive = archive(WORLD, version, "minecraft:overworld", "minecraft:plains", 0);
        var spec = specification("minecraft:plains");
        rejects(() -> high(archive, spec, 0, 16, 0L, "", 1), "显式续算必须给出新版token");
        rejects(() -> high(archive, spec, 0, 16, 1L, "f".repeat(64), 1), "旧P4.1裸摘要不能继续");
        rejects(() -> high(archive, spec, 0, 16, 1L, "wsl-p44-v2:" + "f".repeat(64), 1),
                "未知未来输入版本不能默认兼容");
        rejects(() -> high(archive, spec, 0, 16, null,
                RecoveryInputIdentity.TOKEN_PREFIX + "f".repeat(64), 1), "token与续算下标必须成对");
    }

    private static String checkpointIdentity(ClientStructureEvidence.Observation observed,
                                             String version) throws Exception {
        var archive = archive(WORLD, version, "minecraft:overworld", "minecraft:plains", 0);
        var spec = specification("minecraft:plains");
        var request = new SeedRecoveryService.Request(observed, List.of(SALT), 0, 4096,
                high(archive, spec, 0, 16, null, "", 1));
        var input = ClientStructureEvidence.constraints(observed, SALT, 0, 4096);
        var low = controlledOutcome(input, LocalSmtSeedSolver.Status.BOUNDED_MODEL_EXHAUSTED);
        var partition = Full64High16ShardPlan.partition(1, 0, 16);
        String token = RecoveryInputIdentity.conditionalToken(request, input, low, partition);
        RecoveryInputIdentity.requireCurrentToken(token);
        check(RecoveryInputIdentity.sha256(token).length() == 64, "新版身份必须有明确格式和SHA256");

        // 同一组low48数字在不同结构前提下不能复用旧高16下标。
        var changedSalt = ClientStructureEvidence.constraints(observed, SALT + 227, 0, 4096);
        different(token, RecoveryInputIdentity.conditionalToken(request, changedSalt, low, partition), "Salt");
        var changedModels = new SolverResearchInput(Map.of(ClientStructureEvidence.SET_ID,
                new RandomSpreadSpec(ClientStructureEvidence.SET_ID, 33, 8,
                        RandomSpreadSpec.Distribution.LINEAR, SALT)), input.constraints(), input.searchWindow());
        different(token, RecoveryInputIdentity.conditionalToken(request, changedModels, low, partition), "结构间距");
        var shiftedWindow = ClientStructureEvidence.constraints(observed, SALT, 1, 4096);
        different(token, RecoveryInputIdentity.conditionalToken(request, shiftedWindow, low, partition), "低48域");
        var changedConstraints = new ArrayList<>(input.constraints());
        var first = changedConstraints.getFirst();
        changedConstraints.set(0, StartPlacementConstraint.inferredAlternatives(first.worldFingerprint(),
                first.gameVersion(), first.dimension(), first.structureSetId(), first.alternativeStartChunks(),
                StartPlacementConstraint.Split.HOLDOUT));
        different(token, RecoveryInputIdentity.conditionalToken(request, new SolverResearchInput(
                input.models(), changedConstraints, input.searchWindow()), low, partition), "结构证据角色");
        var changedQualification = new LocalSmtSeedSolver.Outcome(low.status(), 1, 0, 0,
                List.of(new LocalSmtSeedSolver.VerifiedLow48(2731, 2, 1)), "资格变更", true);
        different(token, RecoveryInputIdentity.conditionalToken(request, input, changedQualification, partition),
                "候选FIT资格");
        var incomplete = controlledOutcome(input, LocalSmtSeedSolver.Status.RESULT_CAP);
        different(token, RecoveryInputIdentity.conditionalToken(request, input, incomplete, partition), "模型完成状态");
        different(token, RecoveryInputIdentity.conditionalToken(request, input, low,
                Full64High16ShardPlan.partition(1, 1, 16)), "高16分片");
        var changedQuart = new SeedRecoveryService.Request(observed, List.of(SALT), 0, 4096,
                high(archive(WORLD, version, "minecraft:overworld", "minecraft:plains", 1),
                        spec, 0, 16, null, "", 1));
        different(token, RecoveryInputIdentity.conditionalToken(changedQuart, input, low, partition), "真实quart");
        var changedModel = new SeedRecoveryService.Request(observed, List.of(SALT), 0, 4096,
                high(archive, specification("minecraft:desert"), 0, 16, null, "", 1));
        different(token, RecoveryInputIdentity.conditionalToken(changedModel, input, low, partition), "气候参数模型");

        var resumed = new SeedRecoveryService.Request(observed, List.of(SALT), 0, 4096,
                high(archive, spec, 0, 16, 1L, token, 128));
        check(token.equals(RecoveryInputIdentity.conditionalToken(resumed, input, low, partition)),
                "新的本轮预算/下标不应改变相同完整搜索域的身份");
        var reorderedBatch = new SeedRecoveryService.Request(observed, List.of(SALT + 227, SALT), 0, 4096,
                high(archive, spec, 0, 16, null, "", 1));
        check(token.equals(RecoveryInputIdentity.conditionalToken(reorderedBatch, input, low, partition)),
                "同批其他Salt不应污染当前独立假设的续算身份");
        var differentDiagnostics = new LocalSmtSeedSolver.Outcome(low.status(), 15, 3, 4,
                low.candidates(), "不同诊断日志", low.incompleteRejectionPaths());
        check(token.equals(RecoveryInputIdentity.conditionalToken(request, input, differentDiagnostics, partition)),
                "非稳定日志、耗时相关统计不能污染输入身份");

        var stale = new SeedRecoveryService.Request(observed, List.of(SALT), 0, 4096,
                high(archive, spec, 0, 16, 1L, RecoveryInputIdentity.TOKEN_PREFIX + "0".repeat(64), 1));
        rejects(() -> new SeedRecoveryService(i -> low).recover(stale), "不匹配token不得进入高16枚举");
        String unstableToken = RecoveryInputIdentity.conditionalToken(request, input, incomplete, partition);
        var unstable = new SeedRecoveryService.Request(observed, List.of(SALT), 0, 4096,
                high(archive, spec, 0, 16, 1L, unstableToken, 1));
        rejects(() -> new SeedRecoveryService(i -> incomplete).recover(unstable),
                "候选集截断即使摘要相同也不得跨次续算");
        return token;
    }

    private static void reportContract(Path root, Path evidence,
            SeedRecoveryService.Result batch, String token) throws Exception {
        String encoded = RecoveryReport.json(batch);
        var json = JsonParser.parseString(encoded).getAsJsonObject();
        check(json.get("format").getAsString().equals("WorldSeedLabRecovery")
                && json.get("schema").getAsInt() == 1 && !json.get("ready_for_mining").getAsBoolean(),
                "报告格式和挖矿门槛必须明确");
        var hypotheses = json.getAsJsonArray("hypotheses");
        check(hypotheses.size() == 2
                && hypotheses.get(0).getAsJsonObject().getAsJsonObject("low48")
                    .getAsJsonArray("candidates").get(0).getAsJsonObject().get("hex")
                    .getAsString().equals("000000000aab"), "低48候选字段可独立机器复核");
        check(!encoded.contains(WORLD) && !encoded.contains(evidence.toString())
                && !encoded.contains("quart_x") && !encoded.contains("worldFingerprint")
                && !encoded.contains("-14192"), "报告不能输出HMAC、路径或原始坐标");
        for (var item : hypotheses) {
            var low = item.getAsJsonObject().getAsJsonObject("low48");
            check(low.get("bounded_model_complete").getAsBoolean()
                    && !low.get("full_domain_complete").getAsBoolean()
                    && low.get("incomplete_rejection_paths").getAsBoolean(),
                    "4096受控窗口和有限拒绝路径不得包装为全部低48完成");
        }

        var budget = new Full64BiomeCandidateSearch.SearchBudget(0, 1, 1, 1000, 64);
        var full = new SeedRecoveryService.High16Result(
                SeedRecoveryService.Mode.EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS,
                "worldseedlab:synthetic_report_test", "UNVERIFIED_OR_CUSTOM", budget,
                Full64High16ShardPlan.partition(1, 0, 1), token,
                SeedRecoveryService.High16Completion.REQUESTED_WINDOW_COMPLETE,
                1, 1, 0, 0, 65536,
                List.of(new Full64BiomeCandidateSearch.Candidate64(Long.MIN_VALUE + 2731, 3, 2)));
        var first = batch.hypotheses().getFirst();
        var controlledReport = new SeedRecoveryService.Result(batch.gameVersion(), batch.dimension(),
                SeedRecoveryService.Mode.EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS, batch.status(), batch.evidence(),
                List.of(new SeedRecoveryService.HypothesisResult(first.structureModel(), first.low48(), full)));
        var fullJson = JsonParser.parseString(RecoveryReport.json(controlledReport)).getAsJsonObject()
                .getAsJsonArray("hypotheses").get(0).getAsJsonObject().getAsJsonObject("high16");
        var decimal = fullJson.getAsJsonArray("candidates").get(0).getAsJsonObject().get("signed_decimal");
        check(decimal.getAsJsonPrimitive().isString()
                && decimal.getAsString().equals(Long.toString(Long.MIN_VALUE + 2731))
                && !fullJson.get("full_domain_complete").getAsBoolean(),
                "负数64位必须无精度损失，单次一个高位不等于全部65536");
        String text = RecoveryReport.text(controlledReport);
        check(text.contains("RECOVERY_RESUME_TOKEN=" + token)
                && text.contains("P4.1_INPUT_SHA256=" + RecoveryInputIdentity.sha256(token))
                && text.contains("READY_FOR_MINING=false"), "文本与JSON应使用同一受限结果语义");

        Path report = root.resolve("result.json");
        RecoveryReport.writeNew(report, batch);
        rejects(() -> RecoveryReport.writeNew(report, controlledReport), "报告禁止覆盖原文件");
        check(Files.readString(report).equals(encoded), "覆盖拒绝后原文件必须保持不变");
        String previous = System.getProperty("recoveryReport");
        try {
            System.setProperty("recoveryReport", report.toString());
            try {
                RealClientSeedRecoveryCli.main(new String[] {root.resolve("missing.zip").toString()});
                throw new AssertionError("已有报告必须先于证据/Z3拒绝");
            } catch (java.io.IOException expected) {
                check(expected.getMessage().contains("报告目标已存在"), "报告目标必须先于导入预检");
            }
        } finally {
            restoreProperty("recoveryReport", previous);
        }
        Path linkedParent = root.resolve("linked-output");
        Files.createSymbolicLink(linkedParent, root);
        rejects(() -> RecoveryReport.validateNewTarget(linkedParent.resolve("new.json")),
                "报告路径不得跟随父目录符号链接");
        Path linkedFile = root.resolve("linked-report.json");
        Files.createSymbolicLink(linkedFile, report);
        rejects(() -> RecoveryReport.writeNew(linkedFile, batch), "报告不得跟随目标符号链接");
    }

    private static void importerGuards(Path root, Path evidence, String version) throws Exception {
        Path linked = root.resolve("linked-evidence");
        Files.createSymbolicLink(linked, evidence);
        rejects(() -> ClientStructureEvidence.inspect(linked, version), "导入器拒绝顶层符号链接目录");
        Path zip = root.resolve("duplicate.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < 2; i++) {
                String name = "client-auto-desert-" + i + ".properties";
                out.putNextEntry(new ZipEntry(name));
                out.write(Files.readAllBytes(evidence.resolve(name)));
                out.closeEntry();
            }
        }
        // 两个等长成员名在本地/中央目录同时改为同名，构造真正的歧义ZIP。
        byte[] bytes = Files.readAllBytes(zip);
        String binary = new String(bytes, StandardCharsets.ISO_8859_1).replace(
                "client-auto-desert-1.properties", "client-auto-desert-0.properties");
        Files.write(zip, binary.getBytes(StandardCharsets.ISO_8859_1));
        rejects(() -> ClientStructureEvidence.inspect(zip, version), "ZIP重名轮廓不可静默接受");
    }

    private static void defaultCliIsReadOnly(Path empty) throws Exception {
        String previous = System.getProperty("recoveryReport");
        PrintStream stdout = System.out;
        var bytes = new ByteArrayOutputStream();
        try (var capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.clearProperty("recoveryReport");
            System.setOut(capture);
            RealClientSeedRecoveryCli.main(new String[] {empty.toString()});
        } finally {
            System.setOut(stdout);
            restoreProperty("recoveryReport", previous);
        }
        try (var paths = Files.list(empty)) {
            check(paths.findAny().isEmpty(), "默认CLI不得新增报告/证据文件");
        }
        check(bytes.toString(StandardCharsets.UTF_8).contains("RESULT=INSUFFICIENT_GEOMETRY_EVIDENCE"),
                "空证据CLI应保留明确的失败关闭文本结果");
        String oldResume = System.getProperty("recoveryHigh16Resume");
        try {
            System.setProperty("recoveryHigh16Resume", "0");
            rejects(() -> RealClientSeedRecoveryCli.main(new String[] {empty.toString()}),
                    "没有自定义模型时不得静默忽略高16续算参数");
        } finally {
            restoreProperty("recoveryHigh16Resume", oldResume);
        }
    }

    private static ClientQuartEvidenceArchive.Archive archive(String world, String version,
            String dimension, String biome, int shift) {
        var session = new ClientQuartEvidenceArchive.Session(world, version, dimension);
        for (int i = 0; i < 5; i++) session.addQuart(i * 512 + shift, 16, 0, biome);
        return session.snapshot();
    }

    private static P40CustomClimateModel.Specification specification(String biome) {
        var span = Climate.Parameter.span(-1f, 1f);
        return new P40CustomClimateModel.Specification("worldseedlab:synthetic_pipeline_test",
                P40CustomClimateModel.SAMPLER,
                List.of(Pair.of(Climate.parameters(span, span, span, span, span, span, 0f), biome)),
                Set.of(biome));
    }

    private static SeedRecoveryService.High16Request high(ClientQuartEvidenceArchive.Archive archive,
            P40CustomClimateModel.Specification spec, int shard, int shards, Long resume, String token, long max) {
        return new SeedRecoveryService.High16Request(
                SeedRecoveryService.Mode.EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS,
                archive, spec, shard, shards, resume, token, max);
    }

    private static LocalSmtSeedSolver.Outcome controlledOutcome(SolverResearchInput input,
                                                               LocalSmtSeedSolver.Status status) {
        int salt = input.models().get(ClientStructureEvidence.SET_ID).salt();
        long seed = (2731L + SALT - salt) & Full64BiomeCandidateSearch.LOW48_MASK;
        int fits = (int) input.constraints().stream()
                .filter(c -> c.split() == StartPlacementConstraint.Split.FIT).count();
        return new LocalSmtSeedSolver.Outcome(status, 2, 0, 0,
                List.of(new LocalSmtSeedSolver.VerifiedLow48(seed, fits, input.constraints().size() - fits)),
                "受控后端；非真实Z3结果", true);
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) System.clearProperty(name); else System.setProperty(name, previous);
    }

    private static void different(String first, String second, String changed) {
        check(!first.equals(second), changed + "改变必须使旧断点身份失效");
    }

    private static void rejects(CheckedAction action, String message) throws Exception {
        try { action.run(); }
        catch (IllegalArgumentException | java.io.IOException expected) { return; }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
