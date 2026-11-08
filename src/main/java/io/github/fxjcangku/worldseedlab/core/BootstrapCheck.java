package io.github.fxjcangku.worldseedlab.core;

import java.util.Set;

/** 所有目标版本共用的Java运行门槛和明确版本白名单。 */
public final class BootstrapCheck {
    private static final Set<String> TARGETS = Set.of("26.1.2", "26.2", "26.3");

    private BootstrapCheck() {
    }

    public static Report inspect(int javaFeature, String minecraftVersion) {
        if (javaFeature < 25) {
            return new Report(false, "需要 Java 25 或更高版本");
        }
        if (!supportsMinecraftVersion(minecraftVersion)) {
            return new Report(false, "当前 Minecraft 版本不在受支持范围: " + minecraftVersion);
        }
        return new Report(true, "运行环境自检通过");
    }

    /** 支持某版本并不允许把另一版本的证据、校准或真值当作该版本使用。 */
    public static boolean supportsMinecraftVersion(String version) {
        return version != null && TARGETS.contains(version);
    }

    public record Report(boolean ok, String message) {
    }
}

