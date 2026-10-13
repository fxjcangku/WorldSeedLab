package io.github.fxjcangku.worldseedlab.core.generation;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 普通客户端可观察到的维度描述。
 *
 * <p>维度高度、环境标志与海平面不是服务器完整生成器配置，
 * 也不意味着能重建自定义地形或得知真实世界种子。
 */
public record DimensionProfile(
        String worldFingerprint,
        String dimension,
        String clientVersion,
        int minBuildY,
        int maxBuildYExclusive,
        int seaLevel,
        boolean skylight,
        boolean ceiling,
        Boolean ultraWarm,
        Instant observedAt
) {
    private static final Pattern FINGERPRINT = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public DimensionProfile {
        if (worldFingerprint == null || !FINGERPRINT.matcher(worldFingerprint).matches()) {
            throw new IllegalArgumentException("invalid local world fingerprint");
        }
        if (dimension == null || !RESOURCE_ID.matcher(dimension).matches()) {
            throw new IllegalArgumentException("invalid dimension id");
        }
        if (clientVersion == null || clientVersion.isBlank() || clientVersion.length() > 32) {
            throw new IllegalArgumentException("invalid game version");
        }
        if (minBuildY < -4096 || maxBuildYExclusive > 8192
                || maxBuildYExclusive <= minBuildY || maxBuildYExclusive - minBuildY > 4096) {
            throw new IllegalArgumentException("invalid dimension height bounds");
        }
        Objects.requireNonNull(observedAt, "observedAt");
    }

    public int totalHeight() {
        return maxBuildYExclusive - minBuildY;
    }
}
