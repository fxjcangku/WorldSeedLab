package io.github.fxjcangku.worldseedlab.core.compatibility;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 仅供已知种子的受控世界使用的独立结构起点参考。
 *
 * <p>REFERENCE 的真实性必须先由 Minecraft 实机世界的生成数据确认；
 * 不允许把普通玩家的 /wsl mark 观察位置变成这种参考。
 */
public record KnownSeedReference(
        String worldFingerprint,
        String dimension,
        String gameVersion,
        String structureId,
        long knownWorldSeed,
        int regionX,
        int regionZ,
        Optional<StartChunk> expectedStart,
        Split split,
        ReferenceOrigin referenceOrigin
) {
    private static final Pattern FINGERPRINT = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public KnownSeedReference {
        if (worldFingerprint == null || !FINGERPRINT.matcher(worldFingerprint).matches()) {
            throw new IllegalArgumentException("invalid world fingerprint");
        }
        if (dimension == null || structureId == null || !ID.matcher(dimension).matches()
                || !ID.matcher(structureId).matches()) {
            throw new IllegalArgumentException("invalid namespaced ID");
        }
        if (!"26.1.2".equals(gameVersion) && !"26.2".equals(gameVersion)) {
            throw new IllegalArgumentException("unsupported Minecraft reference version");
        }
        Objects.requireNonNull(expectedStart, "expectedStart");
        Objects.requireNonNull(split, "split");
        Objects.requireNonNull(referenceOrigin, "referenceOrigin");
    }

    public record StartChunk(int x, int z) {
    }

    public enum Split {
        TRAINING,
        HOLDOUT
    }

    /** 模拟夹具永远不能用作真实游戏生成规律的确认依据。 */
    public enum ReferenceOrigin {
        SYNTHETIC_FIXTURE,
        VERIFIED_GAME_REFERENCE
    }
}
