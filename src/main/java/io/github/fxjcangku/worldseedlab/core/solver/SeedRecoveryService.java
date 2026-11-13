package io.github.fxjcangku.worldseedlab.core.solver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 显式离线恢复管线：完成输入门控后，逐个 Salt 假设运行既有求解器。
 *
 * <p>服务不读取系统属性、不打印、不写报告；输入与结果均为不可变类型。
 * 模型候选和未知服务器真值严格分离，任何结果都不授予矿物预测资格。
 * 原有 LCG、SMT 与高16枚举算法保持在各自实现中。
 */
public final class SeedRecoveryService {
    public enum Mode {
        STRUCTURE_LOW48,
        EXPLICIT_VANILLA_OVERWORLD_HYPOTHESIS,
        EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS
    }

    public enum ResultStatus {
        INSUFFICIENT_GEOMETRY_EVIDENCE,
        MODEL_REGISTRY_INCOMPATIBLE,
        MODEL_HYPOTHESES_EVALUATED
    }

    public enum High16Completion {
        REQUESTED_WINDOW_COMPLETE, EVALUATION_BUDGET, TIME_BUDGET,
        RESULT_CAP, CANCELLED, MODEL_REGISTRY_INCOMPATIBLE
    }

    /** 分片域固定；续算只改变本轮起点和资源预算，不能改变输入身份。 */
    public record High16Request(
            Mode mode, ClientQuartEvidenceArchive.Archive archive,
            P40CustomClimateModel.Specification customModel,
            int shardIndex, int shardCount, Long resumeIndex,
            String expectedResumeToken, long maximumEvaluations) {
        public High16Request {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(archive);
            expectedResumeToken = Objects.requireNonNull(expectedResumeToken);
            if (mode == Mode.STRUCTURE_LOW48
                    || (mode == Mode.EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS) != (customModel != null)
                    || shardCount < 1 || shardCount > Full64High16ShardPlan.MAX_SHARDS
                    || shardIndex < 0 || shardIndex >= shardCount
                    || maximumEvaluations < 1 || maximumEvaluations > 1_000_000
                    || (resumeIndex != null && resumeIndex < 0)) {
                throw new IllegalArgumentException("高16模型、分片或预算参数不完整");
            }
            if (!expectedResumeToken.isEmpty()) {
                RecoveryInputIdentity.requireCurrentToken(expectedResumeToken);
            }
            if ((resumeIndex == null) != expectedResumeToken.isEmpty()) {
                throw new IllegalArgumentException("续算摘要必须与显式续算位置共同提供");
            }
            if (mode == Mode.EXPLICIT_VANILLA_OVERWORLD_HYPOTHESIS
                    && (shardIndex != 0 || shardCount != 1 || resumeIndex != null
                        || !expectedResumeToken.isEmpty())) {
                throw new IllegalArgumentException("原版假设入口不接收自定义模型分片断点");
            }
        }

        public static High16Request vanilla(ClientQuartEvidenceArchive.Archive archive) {
            return new High16Request(Mode.EXPLICIT_VANILLA_OVERWORLD_HYPOTHESIS,
                    archive, null, 0, 1, null, "", 1_000_000);
        }
    }

    public record Request(ClientStructureEvidence.Observation structures,
                          List<Integer> salts, long low48StartInclusive,
                          long low48EndExclusive, High16Request high16) {
        public Request {
            Objects.requireNonNull(structures);
            salts = List.copyOf(salts);
            if (salts.isEmpty() || salts.size() > 4
                    || new HashSet<>(salts).size() != salts.size()) {
                throw new IllegalArgumentException("每次需要1..4个不重复的显式Salt假设");
            }
            // 在任何外部进程启动之前检查位域；资源上限与既有P3.9一致。
            new CandidateSeedSearch.SearchWindow(low48StartInclusive,
                    low48EndExclusive, 1_000_000, 32, 90_000);
            if (high16 != null && salts.size() != 1
                    && high16.resumeIndex() != null) {
                throw new IllegalArgumentException("续算必须一次选择一个Salt假设及其专属摘要");
            }
        }

        public Mode mode() { return high16 == null ? Mode.STRUCTURE_LOW48 : high16.mode(); }
    }

    /** 只包含可报告计数，不携带HMAC、原始坐标或输入文件路径。 */
    public record EvidenceSummary(int footprintFiles, int uniqueFootprints,
                                  int independentRegions, int structureFit,
                                  int structureHoldout, int quartFit, int quartHoldout) {}

    public record Low48Result(CandidateSeedSearch.SearchWindow window,
                              LocalSmtSeedSolver.Outcome outcome) {
        public Low48Result {
            Objects.requireNonNull(window);
            Objects.requireNonNull(outcome);
        }

        /** 只指有限拒绝路径模型已无更多结果，不代表所有Random.nextInt路径。 */
        public boolean boundedModelComplete() {
            return outcome.status() == LocalSmtSeedSolver.Status.BOUNDED_MODEL_EXHAUSTED;
        }

        public boolean fullDomainComplete() {
            return boundedModelComplete() && !outcome.incompleteRejectionPaths()
                    && window.startInclusive() == 0
                    && window.endExclusive() == CandidateSeedSearch.TOTAL_48;
        }
    }

    public record High16Result(
            Mode mode, String generatorProfile, String modelTrust,
            Full64BiomeCandidateSearch.SearchBudget budget,
            Full64High16ShardPlan.Partition partition, String resumeToken,
            High16Completion completion, long evaluations, long nextIndex,
            long fitRejected, long holdoutRejected, long domainSize,
            List<Full64BiomeCandidateSearch.Candidate64> candidates) {
        public High16Result { candidates = List.copyOf(candidates); }

        public boolean requestedWindowComplete() {
            return completion == High16Completion.REQUESTED_WINDOW_COMPLETE
                    && nextIndex == budget.toIndexExclusive()
                    && evaluations == budget.toIndexExclusive() - budget.fromIndexInclusive();
        }

        /** 仅本次完整检查全部输入low48的高位时为真；不会自动拼接旧分片。 */
        public boolean fullDomainComplete() {
            return requestedWindowComplete() && budget.fromIndexInclusive() == 0
                    && budget.toIndexExclusive() == domainSize;
        }
    }

    public record HypothesisResult(RandomSpreadSpec structureModel,
                                   Low48Result low48, High16Result high16) {}

    public record Result(String gameVersion, String dimension, Mode mode,
                         ResultStatus status, EvidenceSummary evidence,
                         List<HypothesisResult> hypotheses) {
        public Result { hypotheses = List.copyOf(hypotheses); }
        public boolean readyForMining() { return false; }
        public String serverSeedStatus() { return "UNKNOWN"; }
        public String orePredictionStatus() { return "UNVERIFIED"; }
    }

    /** 后端可独立测试或替换；服务仍对返回候选重新执行精确FIT/HOLDOUT。 */
    @FunctionalInterface
    public interface Low48Backend {
        LocalSmtSeedSolver.Outcome search(SolverResearchInput input) throws IOException;
    }

    private final Low48Backend low48Backend;

    public SeedRecoveryService() {
        this(input -> LocalSmtSeedSolver.search(input,
                new SymbolicRandomSpread.Options(3, 90_000, 64), "z3", 16));
    }

    public SeedRecoveryService(Low48Backend low48Backend) {
        this.low48Backend = Objects.requireNonNull(low48Backend);
    }

    public Result recover(Request request) throws IOException {
        Objects.requireNonNull(request);
        var observed = request.structures();
        var high = request.high16();
        int regions = observed.independentRegions(32);
        int used = Math.min(regions, 32);
        var summary = new EvidenceSummary(observed.filesSeen(), observed.sites().size(),
                regions, regions < 3 ? 0 : used - 1, regions < 3 ? 0 : 1,
                high == null ? 0 : 3, high == null ? 0 : high.archive().samples().size() - 3);

        // 旧CLI在Z3结束后才检查quart作用域；现在任何已知跨源输入先失败关闭。
        if (high != null && !observed.sites().isEmpty()) {
            var scope = high.archive().scope();
            if (!Objects.equals(observed.worldFingerprint(), scope.worldFingerprint())
                    || !observed.version().equals(scope.gameVersion())
                    || !Objects.equals(observed.dimension(), scope.dimension())) {
                throw new IllegalArgumentException("真实quart与完整轮廓的世界、维度或版本不一致");
            }
        }
        if (regions < 3) {
            return result(request, summary, ResultStatus.INSUFFICIENT_GEOMETRY_EVIDENCE, List.of());
        }
        if (high != null && !registryCompatible(high)) {
            return result(request, summary, ResultStatus.MODEL_REGISTRY_INCOMPATIBLE, List.of());
        }

        // 所有Salt的结构门槛先通过，避免无效后续输入已经消耗前一模型的Z3预算。
        var inputs = new ArrayList<SolverResearchInput>();
        for (int salt : request.salts()) {
            inputs.add(ClientStructureEvidence.constraints(observed, salt,
                    request.low48StartInclusive(), request.low48EndExclusive()));
        }
        var results = new ArrayList<HypothesisResult>();
        for (var input : inputs) {
            var low = checkedOutcome(input, low48Backend.search(input));
            High16Result full = high == null || low.candidates().isEmpty()
                    ? null : searchHigh16(request, input, low);
            results.add(new HypothesisResult(input.models().get(ClientStructureEvidence.SET_ID),
                    new Low48Result(input.searchWindow(), low), full));
        }
        return result(request, summary, ResultStatus.MODEL_HYPOTHESES_EVALUATED, results);
    }

    private static boolean registryCompatible(High16Request high) {
        return high.archive().samples().stream().allMatch(sample ->
                high.mode() == Mode.EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS
                        ? high.customModel().possibleBiomes().contains(sample.observedBiome())
                        : sample.observedBiome().startsWith("minecraft:"));
    }

    private static LocalSmtSeedSolver.Outcome checkedOutcome(
            SolverResearchInput input, LocalSmtSeedSolver.Outcome outcome) {
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(outcome.status());
        int fit = (int) input.constraints().stream()
                .filter(c -> c.split() == StartPlacementConstraint.Split.FIT).count();
        int holdout = input.constraints().size() - fit;
        var unique = new HashSet<Long>();
        if (outcome.candidates().size() > 16) {
            throw new IllegalArgumentException("低48后端越过P3.9候选上限");
        }
        for (var candidate : outcome.candidates()) {
            if (!unique.add(candidate.seedLow48()) || candidate.fitMatches() != fit
                    || candidate.holdoutMatches() != holdout
                    || candidate.seedLow48() < input.searchWindow().startInclusive()
                    || candidate.seedLow48() >= input.searchWindow().endExclusive()
                    || input.constraints().stream().anyMatch(c -> !c.matchesPlacement(
                            candidate.seedLow48(), input.models().get(c.structureSetId())))) {
                throw new IllegalArgumentException("低48后端结果未通过当前精确FIT/HOLDOUT复核");
            }
        }
        return new LocalSmtSeedSolver.Outcome(outcome.status(), outcome.solverCalls(),
                outcome.rejectedByHoldout(), outcome.rejectedByExactModel(),
                outcome.candidates().stream().sorted(Comparator.comparingLong(
                        LocalSmtSeedSolver.VerifiedLow48::seedLow48)).toList(),
                outcome.description(), outcome.incompleteRejectionPaths()
                        || input.models().values().stream().anyMatch(model -> {
                            int bound = model.offsetBound();
                            return (bound & (bound - 1)) != 0;
                        }));
    }

    private static High16Result searchHigh16(Request request, SolverResearchInput input,
                                             LocalSmtSeedSolver.Outcome low) throws IOException {
        var high = request.high16();
        var partition = Full64High16ShardPlan.partition(low.candidates().size(),
                high.shardIndex(), high.shardCount());
        long resume = high.resumeIndex() == null ? partition.fromIndex() : high.resumeIndex();
        if (resume < partition.fromIndex() || resume >= partition.endExclusive()) {
            throw new IllegalArgumentException("高16续算位置不属于当前分片");
        }
        String token = "";
        if (high.mode() == Mode.EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS) {
            token = RecoveryInputIdentity.conditionalToken(request, input, low, partition);
            if (!high.expectedResumeToken().isEmpty()
                    && !token.equals(high.expectedResumeToken())) {
                throw new IllegalArgumentException("续算输入身份改变，必须重新从本分片起点运行");
            }
            if (resume > partition.fromIndex()
                    && (high.expectedResumeToken().isEmpty()
                        || low.status() != LocalSmtSeedSolver.Status.BOUNDED_MODEL_EXHAUSTED)) {
                throw new IllegalArgumentException("跨次续算需完整稳定的低48模型集合及新版输入摘要");
            }
        }
        var budget = new Full64BiomeCandidateSearch.SearchBudget(resume,
                partition.endExclusive(), Math.min(high.maximumEvaluations(),
                        partition.endExclusive() - resume), 120_000, 64);
        if (high.mode() == Mode.EXPLICIT_CUSTOM_CLIMATE_HYPOTHESIS) {
            var found = P41ConditionalHigh16Search.search(low.candidates(), high.archive(),
                    high.customModel(), budget, Thread.currentThread()::isInterrupted);
            return new High16Result(high.mode(), high.customModel().generatorProfile(),
                    "UNVERIFIED_OR_CUSTOM", budget, partition, token,
                    High16Completion.valueOf(found.completion().name()), found.evaluations(),
                    found.nextIndex(), found.fitRejected(), found.holdoutRejected(),
                    found.domainSize(), found.candidates().stream().map(c ->
                        new Full64BiomeCandidateSearch.Candidate64(c.fullSeedBits(),
                                c.fitMatches(), c.holdoutMatches())).toList());
        }
        var scope = high.archive().scope();
        var model = VanillaOverworldBiomeModel.create(new Full64BiomeCandidateSearch.Scope(
                scope.worldFingerprint(), scope.gameVersion(), scope.dimension(),
                VanillaOverworldBiomeModel.GENERATOR_PROFILE));
        var found = Full64BiomeCandidateSearch.search(low.candidates(),
                high.archive().forVanillaHypothesis(), model, budget,
                Thread.currentThread()::isInterrupted);
        long fitRejected = found.evaluations() - found.holdoutRejected() - found.candidates().size();
        return new High16Result(high.mode(), model.scope().generatorProfile(),
                found.modelTrust().name(), budget, partition, "",
                High16Completion.valueOf(found.completion().name()), found.evaluations(),
                found.nextIndex(), fitRejected, found.holdoutRejected(),
                found.domainSize(), found.candidates());
    }

    private static Result result(Request request, EvidenceSummary evidence,
                                 ResultStatus status, List<HypothesisResult> hypotheses) {
        return new Result(request.structures().version(), request.structures().dimension(),
                request.mode(), status, evidence, hypotheses);
    }
}
