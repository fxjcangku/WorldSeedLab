package io.github.fxjcangku.worldseedlab.core.solver;

import java.nio.file.Path;

/**
 * 开发者离线测试入口：读取显示提供的起点约束，在给定低 48 位窗口中
 * 寻找符合模型的候选。不会自动访问游戏、服务器、聊天内容或账号资料。
 */
public final class SolverResearchCli {
    private SolverResearchCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "用法：gradle -PmcVersion=26.2 -PsolverInput=/path/input.properties p27ResearchCli");
        }
        SolverResearchInput input = SolverResearchInput.load(Path.of(args[0]));
        var result = CandidateSeedSearch.search(input.models(), input.constraints(),
                input.searchWindow(), () -> Thread.currentThread().isInterrupted());

        System.out.println("WorldSeedLab / P2.7 结构种子候选离线实验");
        System.out.println("搜索说明：仅验证指定 random_spread 模型和低48位种子窗口；"
                + "没有从服务器读取真实 Seed");
        System.out.println("状态：" + result.completion());
        System.out.println("本次枚举：" + result.evaluations());
        System.out.println("下一搜索起点：" + result.nextLow48());
        System.out.println("独立留出约束否决数量：" + result.holdoutRejected());
        if (result.candidates().isEmpty()) {
            System.out.println("此已检查区间无符合约束的候选，不代表其它低48位不存在匹配");
        } else {
            for (var candidate : result.candidates()) {
                System.out.println("LOW48=0x" + candidate.hex12()
                        + " fit=" + candidate.fitConstraints()
                        + " holdout=" + candidate.passedHoldoutConstraints()
                        + " status=" + candidate.holdoutStatus());
            }
        }
        System.out.println("注意：候选不等于完整64位世界种子，"
                + "也不代表目标服务器自定义生成参数已经确认。");
    }
}
