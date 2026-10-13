package io.github.fxjcangku.worldseedlab.core.generation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 客户端在世界连接时获知的维度注册键清单。
 *
 * <p>ADVERTISED 表示连接通知的维度标识，不代表维度已访问、地形可重建、
 * 服务器生成器配置可见，或能通过它们直接计算种子。
 * CURRENT_ONLY 表示无法读取服务器公布的完整清单，只有当前维度可信。
 */
public record AdvertisedDimensions(
        String worldFingerprint,
        String clientVersion,
        String currentDimension,
        Source source,
        List<String> dimensionIds,
        Instant observedAt
) {
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public enum Source {
        ADVERTISED,
        CURRENT_ONLY
    }

    public AdvertisedDimensions {
        if (worldFingerprint == null || !HASH.matcher(worldFingerprint).matches()) {
            throw new IllegalArgumentException("invalid scoped world fingerprint");
        }
        if (clientVersion == null || clientVersion.isBlank() || clientVersion.length() > 32) {
            throw new IllegalArgumentException("invalid client version");
        }
        if (currentDimension == null || !RESOURCE.matcher(currentDimension).matches()) {
            throw new IllegalArgumentException("invalid current dimension");
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(dimensionIds, "dimensionIds");
        Objects.requireNonNull(observedAt, "observedAt");
        if (dimensionIds.isEmpty() || dimensionIds.size() > 512) {
            throw new IllegalArgumentException("dimension registry size outside permitted range");
        }
        for (String id : dimensionIds) {
            if (id == null || !RESOURCE.matcher(id).matches() || id.length() > 256) {
                throw new IllegalArgumentException("invalid advertised dimension identifier");
            }
        }
        dimensionIds = dimensionIds.stream().distinct().sorted().toList();
        if (!dimensionIds.contains(currentDimension)) {
            throw new IllegalArgumentException("current dimension must appear in snapshot");
        }
        if (source == Source.CURRENT_ONLY && dimensionIds.size() != 1) {
            throw new IllegalArgumentException("CURRENT_ONLY cannot claim other dimensions");
        }
    }

    /** 非 minecraft 命名空间只表示自定义 ID，尚不证明自定义世界生成。 */
    public long nonVanillaCount() {
        return dimensionIds.stream().filter(id -> !id.startsWith("minecraft:")).count();
    }
}
