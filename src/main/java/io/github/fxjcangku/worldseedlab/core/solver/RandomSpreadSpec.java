package io.github.fxjcangku.worldseedlab.core.solver;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一组已核对的 random_spread 参数。
 *
 * <p>structureSetId 是结构集合的标识，不等于单个建筑类型。
 * Salt/间距/分离距离必须由对应 Minecraft 版本资源或授权测试给出，
 * 严禁根据“看起来像原版”直接硬编码成服务器真实设置。
 *
 * <p>当前模型仅计算 placement 候选点；frequency、exclusion、
 * 结构内容选择与群系校验不包含在此简化规范内。
 */
public record RandomSpreadSpec(
        String structureSetId,
        int spacing,
        int separation,
        Distribution distribution,
        int salt
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public enum Distribution { LINEAR, TRIANGULAR }

    public RandomSpreadSpec {
        if (structureSetId == null || !ID.matcher(structureSetId).matches()) {
            throw new IllegalArgumentException("invalid structure set registry key");
        }
        if (spacing < 1 || spacing > 4096 || separation < 0 || separation >= spacing) {
            throw new IllegalArgumentException("spacing and separation outside valid range");
        }
        Objects.requireNonNull(distribution);
    }

    public int offsetBound() {
        return spacing - separation;
    }

    public int evaluateOffset(LegacyLcg48 random) {
        int bound = offsetBound();
        return switch (distribution) {
            case LINEAR -> random.nextInt(bound);
            case TRIANGULAR -> (random.nextInt(bound) + random.nextInt(bound)) / 2;
        };
    }
}
