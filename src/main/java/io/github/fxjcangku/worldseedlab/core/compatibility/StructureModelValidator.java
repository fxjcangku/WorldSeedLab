package io.github.fxjcangku.worldseedlab.core.compatibility;

import io.github.fxjcangku.worldseedlab.core.compatibility.KnownSeedReference.StartChunk;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 在“已知 Seed + 独立生成起点真值”场景对自研放置规则进行交叉验证。
 *
 * <p>这里验证的是模型逻辑与已知参考数据的匹配度，不会反推出 Seed。
 * 缺少真正的 Minecraft 结构生成对照时，只能给出 UNKNOWN 或证据不足，
 * 不能拿自造的测试输入宣称已兼容某个真实多人服务器。
 */
public final class StructureModelValidator {
    private StructureModelValidator() {
    }

    @FunctionalInterface
    public interface StartPredictor {
        Optional<StartChunk> predict(long seed, int regionX, int regionZ);
    }

    public enum Verdict {
        UNKNOWN,
        INSUFFICIENT_EVIDENCE,
        MODEL_COMPATIBLE,
        MODEL_INCOMPATIBLE
    }

    /** 结果作用域固定在受控已知 Seed 数据，绝不声称已破解真实服务器。 */
    public record Result(Verdict verdict, String gameVersion, String dimension,
                         String structureId, int trainingMatches, int holdoutMatches,
                         int mismatches, String explanation) {
    }

    public static Result evaluate(
            String version,
            String dimension,
            String structure,
            StartPredictor candidateModel,
            List<KnownSeedReference> controls
    ) {
        Objects.requireNonNull(version);
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(structure);
        Objects.requireNonNull(controls);
        if (candidateModel == null) {
            return new Result(Verdict.UNKNOWN, version, dimension, structure,
                    0, 0, 0, "尚未提供已验证生成算法，只能保留 UNKNOWN");
        }

        Set<String> regions = new HashSet<>();
        int training = 0, holdout = 0, mismatch = 0;
        for (KnownSeedReference reference : controls) {
            if (!version.equals(reference.gameVersion())
                    || !dimension.equals(reference.dimension())
                    || !structure.equals(reference.structureId())) {
                throw new IllegalArgumentException("mixed version, dimension or structure references");
            }
            String identity = reference.worldFingerprint() + "|" + reference.knownWorldSeed()
                    + "|" + reference.regionX() + "|" + reference.regionZ();
            if (!regions.add(identity)) {
                throw new IllegalArgumentException("duplicated region among controls or holdouts");
            }
            Optional<StartChunk> prediction = Objects.requireNonNull(
                    candidateModel.predict(reference.knownWorldSeed(),
                            reference.regionX(), reference.regionZ()),
                    "predictor must return Optional");
            if (!prediction.equals(reference.expectedStart())) {
                mismatch++;
            } else if (reference.split() == KnownSeedReference.Split.TRAINING) {
                training++;
            } else {
                holdout++;
            }
        }

        if (controls.isEmpty()) {
            return new Result(Verdict.INSUFFICIENT_EVIDENCE, version, dimension,
                    structure, 0, 0, 0, "没有独立控制世界结构起点参考");
        }
        if (mismatch > 0) {
            return new Result(Verdict.MODEL_INCOMPATIBLE, version, dimension,
                    structure, training, holdout, mismatch,
                    "有已知 Seed 的结构生成起点与模型预测不一致，需修正版本规则或数据");
        }
        if (training < 3 || holdout < 2) {
            return new Result(Verdict.INSUFFICIENT_EVIDENCE, version, dimension,
                    structure, training, holdout, 0,
                    "至少需 3 个训练区域和 2 个独立留出区域进行对照");
        }
        return new Result(Verdict.MODEL_COMPATIBLE, version, dimension,
                structure, training, holdout, 0,
                "仅对这组已知 Seed、版本和结构参考成立；尚未证明真实服务器兼容");
    }
}
