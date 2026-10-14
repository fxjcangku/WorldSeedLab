package io.github.fxjcangku.worldseedlab.core.compatibility;

import io.github.fxjcangku.worldseedlab.core.solver.ClientQuartEvidenceArchive;
import io.github.fxjcangku.worldseedlab.core.solver.Full64BiomeCandidateSearch;
import io.github.fxjcangku.worldseedlab.core.solver.RandomSpreadSpec;
import io.github.fxjcangku.worldseedlab.core.solver.StartPlacementConstraint;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * P3.4 自定义地形的离线条件兼容性审计，不接触客户端、网络、存档或 Seed 获取 API。
 *
 * <p>审计的是「给定 Seed + 给定规则 + 给定可追溯观测是否一致」，而非证明未知
 * 服务器的实际 Seed。原版群系模型的代码可信，与目标服确实采用该模型，是两回事。
 * 未知 Salt、不同生成时期、人工建筑和无法核验的轮廓必须保留歧义，不得计入正例。
 *
 * <p>输出只包含计数和结论，不包含私人坐标、世界 HMAC、Seed 或服务器地址。
 */
public final class P34WorldgenCompatibilityAudit {
    private P34WorldgenCompatibilityAudit() {}

    public enum Verdict {
        MODEL_CONSISTENT, INSUFFICIENT_EVIDENCE, MODEL_CONFLICT, GENERATOR_UNKNOWN
    }

    /** 这是研究者提供的来源声明，并不等于客户端获得了服务端 StructureStart。 */
    public enum StructureProvenance {
        CONTROL_WORLD_SAVED_START,
        CLIENT_CALIBRATED_COMPLETE_PERIMETER,
        SIGHTING_OR_POSSIBLY_ARTIFICIAL
    }

    /** 同一 HMAC 世界身份也可能包含跨版本升级的旧区块。 */
    public enum ChunkHistory {
        CONFIRMED_SINGLE_EPOCH, UNKNOWN, MIXED_OR_PREGENERATED
    }

    public enum SaltKnowledge {
        CONTROL_VERIFIED, EXPLICIT_HYPOTHESIS, UNKNOWN
    }

    public enum BiomeKnowledge {
        CONTROL_VANILLA_VERIFIED, EXPLICIT_VANILLA_HYPOTHESIS, UNKNOWN_OR_CUSTOM
    }

    /** 不把本地世界 HMAC 写入审计输出，但所有证据必须精确属于它。 */
    public record Scope(String worldFingerprint, String gameVersion, String dimension) {
        public Scope {
            new Full64BiomeCandidateSearch.Scope(worldFingerprint, gameVersion,
                    dimension, ClientQuartEvidenceArchive.UNVERIFIED_PROFILE);
        }
    }

    public record StructureEvidence(
            StartPlacementConstraint constraint, StructureProvenance provenance,
            ChunkHistory history
    ) {
        public StructureEvidence {
            Objects.requireNonNull(constraint);
            Objects.requireNonNull(provenance);
            Objects.requireNonNull(history);
            if (provenance == StructureProvenance.CONTROL_WORLD_SAVED_START
                    && constraint.origin() !=
                    StartPlacementConstraint.Origin.REFERENCE_FROM_CONTROL_WORLD) {
                throw new IllegalArgumentException("受控存档真起点与证据类型冲突");
            }
            if (provenance == StructureProvenance.CLIENT_CALIBRATED_COMPLETE_PERIMETER
                    && constraint.origin() != StartPlacementConstraint.Origin.GEOMETRY_ESTIMATE) {
                throw new IllegalArgumentException("客户端校准外轮廓不能伪造服务端真起点");
            }
        }
    }

    public record AxisResult(Verdict verdict, int fitMatches, int holdoutMatches,
                             int mismatches, int excluded, String explanation) {
        public AxisResult {
            Objects.requireNonNull(verdict);
            Objects.requireNonNull(explanation);
            if (fitMatches < 0 || holdoutMatches < 0 || mismatches < 0 || excluded < 0) {
                throw new IllegalArgumentException("审计计数不允许为负");
            }
        }
    }

    public record Report(AxisResult structure, AxisResult biomes, Verdict overall) {
        public Report {
            Objects.requireNonNull(structure);
            Objects.requireNonNull(biomes);
            Objects.requireNonNull(overall);
        }

        /** 可分享的中文摘要：不泄漏低48、高16、原始坐标及服务器世界指纹。 */
        public String chineseSummary() {
            return "WorldSeedLab P3.4 本地生成规则条件审计\n"
                    + "结构：" + structure.verdict() + " | FIT=" + structure.fitMatches()
                    + " HOLDOUT=" + structure.holdoutMatches()
                    + " 不匹配=" + structure.mismatches()
                    + " 排除=" + structure.excluded() + "\n"
                    + structure.explanation() + "\n"
                    + "群系：" + biomes.verdict() + " | FIT=" + biomes.fitMatches()
                    + " HOLDOUT=" + biomes.holdoutMatches()
                    + " 不匹配=" + biomes.mismatches() + "\n"
                    + biomes.explanation() + "\n"
                    + "综合：" + overall + "\n"
                    + "仅对输入模型及独立样本成立；不能证明未知服务端完整64位Seed、"
                    + "Salt、历史区块或矿点。\n";
        }
    }

    /**
     * 对受控 NBT 真起点或受控轮廓校准得到的离散候选并集进行 Java RNG 复核。
     * 目击点/材料簇不得自动用于负例；结构「未出现」也不是强负证据。
     */
    public static AxisResult structures(Scope scope, RandomSpreadSpec spec,
            List<StructureEvidence> evidence, long candidateSeed, SaltKnowledge salt) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(spec);
        Objects.requireNonNull(evidence);
        Objects.requireNonNull(salt);
        if (evidence.size() > 128) {
            throw new IllegalArgumentException("单次结构审计不得超过128个观测");
        }
        Set<Long> usedRegions = new HashSet<>();
        int fit = 0, holdout = 0, failed = 0, excluded = 0;
        boolean unsafeHistory = false;
        for (StructureEvidence item : evidence) {
            Objects.requireNonNull(item);
            var e = item.constraint();
            if (!scope.worldFingerprint().equals(e.worldFingerprint())
                    || !scope.gameVersion().equals(e.gameVersion())
                    || !scope.dimension().equals(e.dimension())
                    || !spec.structureSetId().equals(e.structureSetId())) {
                throw new IllegalArgumentException("结构证据跨世界/维度/版本/结构集合，拒绝混合");
            }
            if (item.history() != ChunkHistory.CONFIRMED_SINGLE_EPOCH) {
                unsafeHistory = true;
                excluded++;
                continue;
            }
            if (item.provenance() == StructureProvenance.SIGHTING_OR_POSSIBLY_ARTIFICIAL) {
                excluded++;
                continue;
            }

            // 所有可能起点所属 Region 都占用：不允许在 FIT 和 HOLDOUT 间重复计分。
            // 保留 P2.9 的稀疏并集，不从外包矩形制造新起点。
            Set<Long> regions = new HashSet<>();
            for (var b : e.alternativeStartChunks()) {
                long minX = Math.floorDiv(b.minX(), spec.spacing());
                long maxX = Math.floorDiv(b.maxX(), spec.spacing());
                long minZ = Math.floorDiv(b.minZ(), spec.spacing());
                long maxZ = Math.floorDiv(b.maxZ(), spec.spacing());
                if ((maxX - minX + 1) * (maxZ - minZ + 1) > 64) {
                    throw new IllegalArgumentException("候选结构起点区域过大");
                }
                for (long x = minX; x <= maxX; x++) {
                    for (long z = minZ; z <= maxZ; z++) {
                        regions.add((x << 32) ^ (z & 0xffff_ffffL));
                        if (regions.size() > 64) {
                            throw new IllegalArgumentException("候选结构Region超过安全上限");
                        }
                    }
                }
            }
            for (long region : regions) {
                if (usedRegions.contains(region)) {
                    throw new IllegalArgumentException("结构FIT/HOLDOUT或重复观测重叠Region");
                }
            }
            usedRegions.addAll(regions);
            if (!e.matchesPlacement(candidateSeed, spec)) {
                failed++;
            } else if (e.split() == StartPlacementConstraint.Split.FIT) {
                fit++;
            } else {
                holdout++;
            }
        }
        if (unsafeHistory) {
            return new AxisResult(Verdict.GENERATOR_UNKNOWN, fit, holdout, failed, excluded,
                    "存在未知生成时期、旧区块或预生成区块；不得合并为单一世界生成模型");
        }
        if (salt == SaltKnowledge.UNKNOWN) {
            return new AxisResult(Verdict.GENERATOR_UNKNOWN, fit, holdout, failed, excluded,
                    "结构Salt/间距/分布规则未知；现有匹配只是假设下的计算");
        }
        if (failed > 0) {
            return new AxisResult(Verdict.MODEL_CONFLICT, fit, holdout, failed, excluded,
                    "可信起点候选与指定RandomSpread参数不一致；仅否定本次模型假设");
        }
        if (fit < 2 || holdout < 1) {
            return new AxisResult(Verdict.INSUFFICIENT_EVIDENCE, fit, holdout, failed, excluded,
                    "至少需要不同Region的2处FIT和1处独立HOLDOUT，目击/人工建筑不计数");
        }
        return new AxisResult(Verdict.MODEL_CONSISTENT, fit, holdout, 0, excluded,
                "结构位置仅与指定RandomSpread参数一致；潜在位置不证明自然生成，"
                        + (salt == SaltKnowledge.EXPLICIT_HYPOTHESIS
                        ? "Salt仍是用户假设" : "参数仅在受控条件经核验"));
    }

    /**
     * 真正 CLIENT_OBSERVED quart CSV 只允许在用户显式指定假设后调用原版模型。
     * 缺少生成器身份、跨代混合时不进行种子高16位诊断。
     */
    public static AxisResult biomes(Scope scope, ClientQuartEvidenceArchive.Archive archive,
            Full64BiomeCandidateSearch.BiomeModel model, long candidateSeed,
            BiomeKnowledge knowledge, ChunkHistory history) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(archive);
        Objects.requireNonNull(knowledge);
        Objects.requireNonNull(history);
        var observed = archive.scope();
        if (!scope.worldFingerprint().equals(observed.worldFingerprint())
                || !scope.gameVersion().equals(observed.gameVersion())
                || !scope.dimension().equals(observed.dimension())) {
            throw new IllegalArgumentException("客户端群系CSV跨世界/维度/版本，拒绝分析");
        }
        if (history != ChunkHistory.CONFIRMED_SINGLE_EPOCH) {
            return new AxisResult(Verdict.GENERATOR_UNKNOWN, 0, 0, 0,
                    archive.samples().size(), "区块生成时期无法证实一致；不得把旧群系当作新Seed约束");
        }
        if (knowledge == BiomeKnowledge.UNKNOWN_OR_CUSTOM) {
            return new AxisResult(Verdict.GENERATOR_UNKNOWN, 0, 0, 0,
                    archive.samples().size(), "自定义群系、噪声参数或生成器身份未知，停止原版高16位筛选");
        }
        if (!"minecraft:overworld".equals(scope.dimension()) || model == null
                || model.trust() !=
                Full64BiomeCandidateSearch.ModelTrust.VERIFIED_TARGET_MINECRAFT_WORLDGEN
                || !model.scope().equals(new Full64BiomeCandidateSearch.Scope(
                        scope.worldFingerprint(), scope.gameVersion(), scope.dimension(),
                        "minecraft:vanilla_overworld"))) {
            throw new IllegalArgumentException("模型版本、维度、作用域或原版生成器接口未验证");
        }
        // 对同一候选只构造一次真实Minecraft采样器；不在客户端 Tick/GUI 中运行。
        var sampler = Objects.requireNonNull(model.prepare(candidateSeed),
                "没有可用的离线群系采样器");
        int fit = 0, holdout = 0, conflicts = 0;
        for (var point : archive.samples()) {
            String predicted = sampler.biomeAtQuart(
                    point.quartX(), point.quartY(), point.quartZ());
            if (!point.observedBiome().equals(predicted)) {
                conflicts++;
            } else if (point.split() == Full64BiomeCandidateSearch.Split.FIT) {
                fit++;
            } else {
                holdout++;
            }
        }
        if (conflicts != 0) {
            return new AxisResult(Verdict.MODEL_CONFLICT, fit, holdout, conflicts, 0,
                    "实际客户端quart与指定原版Overworld模型冲突；不能推定后台没有Seed");
        }
        return new AxisResult(Verdict.MODEL_CONSISTENT, fit, holdout, 0, 0,
                knowledge == BiomeKnowledge.EXPLICIT_VANILLA_HYPOTHESIS
                        ? "客户端quart符合本次原版假设；尚未证明服务器采用此生成器"
                        : "受控世界群系模型与独立观测符合；不延伸到未知服务器");
    }

    /**
     * 受控世界专用：FULL Anvil 存档独立提取的群系使用 CONTROL_WORLD_OBSERVED，
     * 严禁通过 ClientQuartEvidenceArchive.Session 伪装成真实客户端采样。
     * 在未知多人服环境不得使用此入口或自称拥有服务端 NBT 权限。
     */
    public static AxisResult controlledWorldBiomes(
            Scope scope, List<Full64BiomeCandidateSearch.BiomeSample> actualFullChunks,
            Full64BiomeCandidateSearch.BiomeModel model, long candidateSeed,
            BiomeKnowledge knowledge, ChunkHistory history) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(actualFullChunks);
        Objects.requireNonNull(knowledge);
        Objects.requireNonNull(history);
        if (actualFullChunks.size() < 5 || actualFullChunks.size() > 64) {
            throw new IllegalArgumentException("受控存档至少需5个独立FULL群系样本");
        }
        var expected = new Full64BiomeCandidateSearch.Scope(
                scope.worldFingerprint(), scope.gameVersion(), scope.dimension(),
                "minecraft:vanilla_overworld");
        Set<String> locations = new HashSet<>();
        for (int i = 0; i < actualFullChunks.size(); i++) {
            var item = Objects.requireNonNull(actualFullChunks.get(i));
            if (!expected.equals(item.scope())
                    || item.origin() != Full64BiomeCandidateSearch.Origin.CONTROL_WORLD_OBSERVED
                    || item.split() != (i < 3
                    ? Full64BiomeCandidateSearch.Split.FIT
                    : Full64BiomeCandidateSearch.Split.HOLDOUT)
                    || !locations.add(item.quartX() + ":" + item.quartY() + ":" + item.quartZ())) {
                throw new IllegalArgumentException("不能将非受控FULL观测当作真实存档群系");
            }
            for (int j = 0; j < i; j++) {
                var previous = actualFullChunks.get(j);
                long dx = (long) item.quartX() - previous.quartX();
                long dz = (long) item.quartZ() - previous.quartZ();
                if (dx * dx + dz * dz < (long) ClientQuartEvidenceArchive
                        .MIN_HORIZONTAL_QUART_DISTANCE
                        * ClientQuartEvidenceArchive.MIN_HORIZONTAL_QUART_DISTANCE) {
                    throw new IllegalArgumentException("受控存档FIT/HOLDOUT空间距离不足");
                }
            }
        }
        if (history != ChunkHistory.CONFIRMED_SINGLE_EPOCH
                || knowledge == BiomeKnowledge.UNKNOWN_OR_CUSTOM) {
            return new AxisResult(Verdict.GENERATOR_UNKNOWN, 0, 0, 0,
                    actualFullChunks.size(), "生成器模型或区块时期未知，不能引用原版模型复核");
        }
        if (!"minecraft:overworld".equals(scope.dimension()) || model == null
                || model.trust() != Full64BiomeCandidateSearch.ModelTrust
                .VERIFIED_TARGET_MINECRAFT_WORLDGEN || !expected.equals(model.scope())) {
            throw new IllegalArgumentException("受控群系实验的真实目标Minecraft模型不匹配");
        }
        var sampler = Objects.requireNonNull(model.prepare(candidateSeed));
        int fits = 0, holdouts = 0, conflicts = 0;
        for (var item : actualFullChunks) {
            String predicted = sampler.biomeAtQuart(
                    item.quartX(), item.quartY(), item.quartZ());
            if (!item.observedBiome().equals(predicted)) {
                conflicts++;
            } else if (item.split() == Full64BiomeCandidateSearch.Split.FIT) {
                fits++;
            } else {
                holdouts++;
            }
        }
        if (conflicts > 0) {
            return new AxisResult(Verdict.MODEL_CONFLICT, fits, holdouts, conflicts, 0,
                    "受控服务器真实FULL区块群系与指定原版模型冲突；该配置下不能以原版气候推高16");
        }
        return new AxisResult(Verdict.MODEL_CONSISTENT, fits, holdouts, 0, 0,
                "受控服务端FULL Anvil群系与原版气候数学一致；不是CLIENT_OBSERVED");
    }

    public static Report combine(AxisResult structures, AxisResult biomes) {
        Objects.requireNonNull(structures);
        Objects.requireNonNull(biomes);
        Verdict overall;
        if (structures.verdict() == Verdict.MODEL_CONFLICT
                || biomes.verdict() == Verdict.MODEL_CONFLICT) {
            overall = Verdict.MODEL_CONFLICT;
        } else if (structures.verdict() == Verdict.GENERATOR_UNKNOWN
                || biomes.verdict() == Verdict.GENERATOR_UNKNOWN) {
            overall = Verdict.GENERATOR_UNKNOWN;
        } else if (structures.verdict() == Verdict.MODEL_CONSISTENT
                && biomes.verdict() == Verdict.MODEL_CONSISTENT) {
            overall = Verdict.MODEL_CONSISTENT;
        } else {
            overall = Verdict.INSUFFICIENT_EVIDENCE;
        }
        return new Report(structures, biomes, overall);
    }
}
