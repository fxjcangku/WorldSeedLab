package io.github.fxjcangku.worldseedlab.core.recognition;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 根据当前客户端方块组合推断的“疑似建筑”，并非服务器生成器的权威结构起点。
 *
 * <p>置信等级只衡量检测特征数量，不是结构自然生成概率。
 * 所有记录不可直接输入 Seed 约束求解器。
 */
public record StructureCandidate(
        String structureId,
        Grade grade,
        Map<String, Integer> evidenceBlocks,
        int characteristicBlocks,
        String explanation
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public enum Grade {
        WEAK,
        MATERIAL_PATTERN
    }

    public StructureCandidate {
        if (structureId == null || !ID.matcher(structureId).matches()) {
            throw new IllegalArgumentException("invalid candidate structure ID");
        }
        Objects.requireNonNull(grade);
        Objects.requireNonNull(explanation);
        evidenceBlocks = Map.copyOf(evidenceBlocks);
        if (characteristicBlocks < 1 || evidenceBlocks.isEmpty()
                || evidenceBlocks.size() > 16 || explanation.length() > 240) {
            throw new IllegalArgumentException("invalid candidate material signature");
        }
        for (var item : evidenceBlocks.entrySet()) {
            if (!ID.matcher(item.getKey()).matches() || item.getValue() <= 0) {
                throw new IllegalArgumentException("invalid structure material witness");
            }
            if (item.getKey().contains("_ore") || item.getKey().contains(":ore")) {
                throw new IllegalArgumentException("ore evidence must never enter structure signatures");
            }
        }
    }
}
