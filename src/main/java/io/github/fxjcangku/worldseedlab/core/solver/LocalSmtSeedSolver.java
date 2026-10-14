package io.github.fxjcangku.worldseedlab.core.solver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可选本地 Z3 命令行后端：只运行离线 SMT-LIB2 模型，不连接 Minecraft。
 *
 * <p>所有模型必须重新通过既有精确 Java RandomSpread 计算与
 * 独立 HOLDOUT 约束。无外部 Z3 时不偷偷降级为漫长的全空间枚举。
 * Z3 是可替换的通用 SMT 后端，不依赖也不捆绑任何 SeedCrackerX 源码。
 */
public final class LocalSmtSeedSolver {
    private static final Pattern HEX =
            Pattern.compile("seed48\\s+#x([0-9a-fA-F]{12})");
    private static final Pattern BINARY =
            Pattern.compile("seed48\\s+#b([01]{48})");
    private static final Pattern DECIMAL =
            Pattern.compile("seed48\\s+\\(_ bv([0-9]+) 48\\)");
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private LocalSmtSeedSolver() {}

    public enum Status {
        BOUNDED_MODEL_EXHAUSTED,
        RESULT_CAP,
        CANCELLED,
        TIMEOUT_OR_UNKNOWN,
        BACKEND_UNAVAILABLE,
        BACKEND_FAILURE
    }

    public record VerifiedLow48(long seedLow48, int fitMatches, int holdoutMatches) {
        public VerifiedLow48 {
            if (seedLow48 < 0 || seedLow48 >= CandidateSeedSearch.TOTAL_48) {
                throw new IllegalArgumentException("not a 48-bit seed candidate");
            }
        }

        public String low48Hex() {
            return RandomSpreadMath.low48Hex(seedLow48);
        }
    }

    public record Outcome(
            Status status, int solverCalls, long rejectedByHoldout,
            long rejectedByExactModel, List<VerifiedLow48> candidates,
            String description, boolean incompleteRejectionPaths
    ) {
        public Outcome {
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * 整体时间上限适用于全部候选，不是每条查询重新给 30 秒。
     *
     * @param z3Executable 默认传入固定的 "z3"；不会通过 shell 解释用户参数
     */
    public static Outcome search(
            SolverResearchInput input, SymbolicRandomSpread.Options options,
            String z3Executable, int maximumModels
    ) throws IOException {
        Objects.requireNonNull(input);
        Objects.requireNonNull(options);
        if (!"z3".equals(z3Executable) && !"z3.exe".equals(z3Executable)) {
            throw new IllegalArgumentException("only a local standard Z3 executable is supported");
        }
        if (maximumModels < 1 || maximumModels > 64) {
            throw new IllegalArgumentException("SMT model cap must be 1..64");
        }
        SymbolicRandomSpread.validateEvidence(input);

        long deadline = System.nanoTime() + options.timeoutMillis() * 1_000_000L;
        List<Long> excluded = new ArrayList<>();
        List<VerifiedLow48> accepted = new ArrayList<>();
        long badHoldout = 0, badExact = 0;
        Path folder = Files.createTempDirectory("worldseedlab-smt-");
        try {
            Path script = folder.resolve("seed-constraints.smt2");
            Path answer = folder.resolve("z3-output.txt");
            for (int calls = 0; calls < maximumModels; calls++) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (Thread.currentThread().isInterrupted()) {
                    return done(Status.CANCELLED, calls, badHoldout, badExact,
                            accepted, "求解线程收到取消信号", input);
                }
                if (remainingMs < 1) {
                    return done(Status.TIMEOUT_OR_UNKNOWN, calls, badHoldout, badExact,
                            accepted, "本轮总计算时间预算已耗尽", input);
                }
                SymbolicRandomSpread.Options limited = new SymbolicRandomSpread.Options(
                        options.maxRejectionDraws(), Math.min(options.timeoutMillis(), remainingMs),
                        options.maximumFitRegions());
                var model = SymbolicRandomSpread.generate(input, limited, excluded);
                Files.writeString(script, model.smtLib2(), StandardCharsets.UTF_8);

                Process process;
                try {
                    process = new ProcessBuilder(z3Executable, "-smt2", "-memory:512",
                            script.toString())
                            .redirectErrorStream(true)
                            .redirectOutput(answer.toFile())
                            .start();
                } catch (IOException unavailable) {
                    return done(Status.BACKEND_UNAVAILABLE, calls, badHoldout, badExact,
                            accepted, "没有找到 Z3 本地命令；请安装后显式运行", input);
                }

                try {
                    boolean finished = process.waitFor(remainingMs, TimeUnit.MILLISECONDS);
                    if (!finished) {
                        process.destroyForcibly();
                        process.waitFor(1, TimeUnit.SECONDS);
                        return done(Status.TIMEOUT_OR_UNKNOWN, calls + 1,
                                badHoldout, badExact, accepted, "Z3 达到本轮总时间上限", input);
                    }
                } catch (InterruptedException interrupted) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    return done(Status.CANCELLED, calls + 1, badHoldout, badExact,
                            accepted, "当前离线求解线程已被中断", input);
                }

                if (Files.size(answer) > MAX_OUTPUT_BYTES) {
                    return done(Status.BACKEND_FAILURE, calls + 1, badHoldout, badExact,
                            accepted, "Z3 诊断输出过大，已停止", input);
                }
                String output = Files.readString(answer, StandardCharsets.UTF_8).trim();
                String firstLine = output.lines().findFirst().orElse("").trim()
                        .toLowerCase(Locale.ROOT);
                if ("unsat".equals(firstLine)) {
                    return done(Status.BOUNDED_MODEL_EXHAUSTED, calls + 1, badHoldout,
                            badExact, accepted,
                            "仅在当前给定参数、结构起始假设和有限拒绝路径下无更多候选；"
                            + "不能证明服务器真实 Seed 不存在", input);
                }
                if ("unknown".equals(firstLine)) {
                    return done(Status.TIMEOUT_OR_UNKNOWN, calls + 1, badHoldout,
                            badExact, accepted, "SMT 引擎报告 unknown", input);
                }
                if (!"sat".equals(firstLine) || process.exitValue() != 0) {
                    return done(Status.BACKEND_FAILURE, calls + 1, badHoldout, badExact,
                            accepted, "Z3 执行失败或没有返回标准 SAT 模型", input);
                }
                long candidate;
                try {
                    candidate = parseModel(output);
                } catch (IllegalArgumentException invalid) {
                    return done(Status.BACKEND_FAILURE, calls + 1, badHoldout, badExact,
                            accepted, "SMT 模型不含合法低48位种子", input);
                }
                if (excluded.contains(candidate)) {
                    return done(Status.BACKEND_FAILURE, calls + 1, badHoldout, badExact,
                            accepted, "SMT 后端重复了被明确排除的模型", input);
                }
                excluded.add(candidate);

                boolean fitValid = true;
                int fitCount = 0, holdoutCount = 0;
                for (StartPlacementConstraint observation : input.constraints()) {
                    RandomSpreadSpec spec = input.models().get(observation.structureSetId());
                    boolean passed = observation.matchesPlacement(candidate, spec);
                    if (observation.split() == StartPlacementConstraint.Split.FIT) {
                        if (!passed) {
                            fitValid = false;
                            break;
                        }
                        fitCount++;
                    }
                }
                if (!fitValid) {
                    badExact++;
                    continue;
                }

                boolean holdoutValid = true;
                for (StartPlacementConstraint observation : input.constraints()) {
                    if (observation.split() != StartPlacementConstraint.Split.HOLDOUT) continue;
                    RandomSpreadSpec spec = input.models().get(observation.structureSetId());
                    if (!observation.matchesPlacement(candidate, spec)) {
                        holdoutValid = false;
                        break;
                    }
                    holdoutCount++;
                }
                if (!holdoutValid) {
                    badHoldout++;
                    continue;
                }
                accepted.add(new VerifiedLow48(candidate, fitCount, holdoutCount));
            }
            return done(Status.RESULT_CAP, maximumModels, badHoldout, badExact,
                    accepted, "达到 SMT 模型候选数量上限，可调整资源参数继续研究", input);
        } finally {
            try (var files = Files.walk(folder)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /** 不解析不可信的 arbitrary output；只接受 Z3 get-value 的48位标准形式。 */
    public static long parseModel(String model) {
        Objects.requireNonNull(model);
        for (Pattern pattern : new Pattern[] {HEX, BINARY, DECIMAL}) {
            Matcher match = pattern.matcher(model);
            if (!match.find()) continue;
            long result = Long.parseUnsignedLong(match.group(1),
                    pattern == HEX ? 16 : pattern == BINARY ? 2 : 10);
            if (result >= CandidateSeedSearch.TOTAL_48) {
                throw new IllegalArgumentException("SMT value exceeds low48 domain");
            }
            return result;
        }
        throw new IllegalArgumentException("missing seed48 model value");
    }

    private static Outcome done(
            Status status, int calls, long badHoldout, long badExact,
            List<VerifiedLow48> accepted, String message,
            SolverResearchInput input
    ) {
        boolean canReject = input.models().values().stream().anyMatch(spec -> {
            int bound = spec.offsetBound();
            return (bound & (bound - 1)) != 0;
        });
        return new Outcome(status, calls, badHoldout, badExact,
                accepted, message + "；结果不是服务器64位完整Seed",
                canReject);
    }
}
