package io.github.fxjcangku.worldseedlab.core;

import java.util.Set;

/** Pure Java bootstrap requirements shared by both Minecraft targets. */
public final class BootstrapCheck {
    private static final Set<String> TARGETS = Set.of("26.1.2", "26.2");

    private BootstrapCheck() {
    }

    public static Report inspect(int javaFeature, String minecraftVersion) {
        if (javaFeature < 25) {
            return new Report(false, "需要 Java 25 或更高版本");
        }
        if (!TARGETS.contains(minecraftVersion)) {
            return new Report(false, "当前 Minecraft 版本不在受支持范围: " + minecraftVersion);
        }
        return new Report(true, "运行环境自检通过");
    }

    public record Report(boolean ok, String message) {
    }
}

