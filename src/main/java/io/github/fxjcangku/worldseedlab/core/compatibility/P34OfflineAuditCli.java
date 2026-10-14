package io.github.fxjcangku.worldseedlab.core.compatibility;

import io.github.fxjcangku.worldseedlab.core.compatibility.P34WorldgenCompatibilityAudit.*;
import io.github.fxjcangku.worldseedlab.core.solver.ClientQuartEvidenceArchive;
import io.github.fxjcangku.worldseedlab.core.solver.SolverResearchInput;
import io.github.fxjcangku.worldseedlab.core.solver.StartPlacementConstraint;
import io.github.fxjcangku.worldseedlab.core.solver.VanillaOverworldBiomeModel;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import java.nio.file.Path;
import java.util.List;

/**
 * 用户显式调用的只读离线审计命令，不是 Seed 自动求解器。
 *
 * <p>候选64位由外部结构/群系求解阶段提供；CLI 不代替独立低48位 Z3 与
 * 全高16域证明。结构 .properties 的权限/真值字段仅是声明，不是签名凭证。
 * 只打印匿名统计；禁止输出原始 CSV 坐标或世界指纹。
 */
public final class P34OfflineAuditCli {
    private P34OfflineAuditCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 6 || !args[2].matches("[a-fA-F0-9]{16}")) {
            throw new IllegalArgumentException("用法: client-quart.csv solver.properties "
                    + "候选完整64位Hex16 CONFIRMED_SINGLE_EPOCH|UNKNOWN|MIXED_OR_PREGENERATED "
                    + "EXPLICIT_HYPOTHESIS|UNKNOWN "
                    + "EXPLICIT_VANILLA_HYPOTHESIS|UNKNOWN_OR_CUSTOM；"
                    + "全部为本机用户显式输入，CONFIRMED仍是研究者声明");
        }
        var archive = ClientQuartEvidenceArchive.read(Path.of(args[0]));
        var structural = SolverResearchInput.load(Path.of(args[1]));
        var observed = archive.scope();
        var scope = new Scope(observed.worldFingerprint(),
                observed.gameVersion(), observed.dimension());
        long candidateBits = Long.parseUnsignedLong(args[2], 16);
        ChunkHistory history = ChunkHistory.valueOf(args[3]);
        if (history == ChunkHistory.CONFIRMED_SINGLE_EPOCH
                && !args[3].equals("CONFIRMED_SINGLE_EPOCH")) {
            throw new IllegalArgumentException("非法生成纪元声明");
        }
        SaltKnowledge salt = SaltKnowledge.valueOf(args[4]);
        BiomeKnowledge biomeKnowledge = BiomeKnowledge.valueOf(args[5]);
        if (salt == SaltKnowledge.CONTROL_VERIFIED
                || biomeKnowledge == BiomeKnowledge.CONTROL_VANILLA_VERIFIED) {
            throw new IllegalArgumentException("真实控制组核验不能由命令行字符串自行授予");
        }
        if (structural.models().size() != 1 || structural.constraints().isEmpty()) {
            throw new IllegalArgumentException("本阶段审计只接收一套明确的结构集合及实际起点约束");
        }
        var spec = structural.models().values().iterator().next();
        List<StructureEvidence> structures = structural.constraints().stream().map(e -> {
            if (!scope.worldFingerprint().equals(e.worldFingerprint())
                    || !scope.gameVersion().equals(e.gameVersion())
                    || !scope.dimension().equals(e.dimension())) {
                throw new IllegalArgumentException("结构约束与客户端quart不是同世界/版本/维度");
            }
            return new StructureEvidence(e,
                    e.origin() == StartPlacementConstraint.Origin.REFERENCE_FROM_CONTROL_WORLD
                            ? StructureProvenance.CONTROL_WORLD_SAVED_START
                            : StructureProvenance.CLIENT_CALIBRATED_COMPLETE_PERIMETER,
                    history);
        }).toList();
        var structureResult = P34WorldgenCompatibilityAudit.structures(
                scope, spec, structures, candidateBits, salt);
        Full64BiomeCandidateSearch.BiomeModel model = null;
        if (biomeKnowledge == BiomeKnowledge.EXPLICIT_VANILLA_HYPOTHESIS
                && history == ChunkHistory.CONFIRMED_SINGLE_EPOCH) {
            model = VanillaOverworldBiomeModel.create(
                    new Full64BiomeCandidateSearch.Scope(scope.worldFingerprint(),
                            scope.gameVersion(), scope.dimension(),
                            VanillaOverworldBiomeModel.GENERATOR_PROFILE));
        }
        var biomeResult = P34WorldgenCompatibilityAudit.biomes(
                scope, archive, model, candidateBits, biomeKnowledge, history);
        System.out.print(P34WorldgenCompatibilityAudit.combine(
                structureResult, biomeResult).chineseSummary());
        System.out.println("限制：生成时期、结构来源、Salt 等由研究者自行核实；"
                + "本入口不读取服务端配置或StructureStart，也不证明该候选经完整域求解。");
    }
}
