package io.github.fxjcangku.worldseedlab.core.solver;

import java.nio.file.Path;

/**
 * 显式调用本机通用 SMT 求解器的开发实验入口。
 * 不在客户端 Tick 中运行；不连接服务器、读取后台 Seed 或自动上传记录。
 */
public final class SymbolicSeedCli {
    private SymbolicSeedCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("用法：gradle -PmcVersion=26.2 "
                    + "-PsolverInput=/path/solver-input.properties p28SmtResearch");
        }
        SolverResearchInput input = SolverResearchInput.load(Path.of(args[0]));
        int maxModels = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        var outcome = LocalSmtSeedSolver.search(input,
                SymbolicRandomSpread.Options.DEFAULT, "z3", maxModels);

        System.out.println("WorldSeedLab / P2.8 结构 Seed 位向量约束研究");
        System.out.println("求解后端：本机 Z3（可选独立程序，未捆绑到 Fabric 模组）");
        System.out.println("求解条件：由用户显式提供结构集合参数、"
                + "拟议起点范围、世界作用域和独立留出样本");
        System.out.println("状态：" + outcome.status());
        System.out.println("已尝试 SMT 模型数：" + outcome.solverCalls());
        System.out.println("被精确 Java RNG 拒绝：" + outcome.rejectedByExactModel());
        System.out.println("被独立 HOLDOUT 拒绝：" + outcome.rejectedByHoldout());
        System.out.println("非二幂随机抽样展开次数有界："
                + (outcome.incompleteRejectionPaths() ? "是" : "否"));
        for (var candidate : outcome.candidates()) {
            System.out.println("LOW48_CANDIDATE=0x" + candidate.low48Hex()
                    + " | fit=" + candidate.fitMatches()
                    + " | holdout=" + candidate.holdoutMatches());
        }
        System.out.println("说明：" + outcome.description());
        System.out.println("数学模型匹配不等于服务器原始 64 位 Seed，"
                + "也不证明自定义矿物生成与该模型一致。");
    }
}
