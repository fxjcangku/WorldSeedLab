package io.github.fxjcangku.worldseedlab.core;

import io.github.fxjcangku.worldseedlab.core.privacy.WorldEpochStore;
import io.github.fxjcangku.worldseedlab.core.privacy.WorldFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** 同地址服务器更换地图后的手动纪元保护测试；不访问任何真实服务器。 */
public final class WorldEpochSmokeTest {
    private WorldEpochSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        String rawSource = "remote:example.private.test:25565";
        byte[] key = "local-test-secret-not-for-sharing".getBytes(StandardCharsets.UTF_8);
        String base = WorldFingerprint.fromLocalSecret(key, rawSource);
        Path temp = Files.createTempDirectory("worldseedlab-epochs-");
        try {
            check(WorldEpochStore.load(temp, base).equals(WorldEpochStore.LEGACY),
                    "first use preserves legacy scope");
            check(WorldEpochStore.effectiveFingerprint(key, rawSource, WorldEpochStore.LEGACY)
                    .equals(base), "legacy fingerprint must remain backward compatible");

            Path oldDirectory = temp.resolve("observations").resolve(base);
            Files.createDirectories(oldDirectory);
            Files.writeString(oldDirectory.resolve("old-sample.txt"), "old evidence retained");

            String firstEpoch = WorldEpochStore.newEpoch(temp, base);
            check(WorldEpochStore.load(temp, base).equals(firstEpoch), "new epoch is persistent");
            String firstScope = WorldEpochStore.effectiveFingerprint(key, rawSource, firstEpoch);
            check(!firstScope.equals(base), "new map must never share old evidence path");
            String secondEpoch = WorldEpochStore.newEpoch(temp, base);
            String secondScope = WorldEpochStore.effectiveFingerprint(key, rawSource, secondEpoch);
            check(!secondScope.equals(firstScope) && !secondScope.equals(base),
                    "each explicit reset must isolate a fresh scope");
            check(Files.readString(oldDirectory.resolve("old-sample.txt"))
                    .equals("old evidence retained"), "reset must not delete previous evidence");
            Path epochPath = temp.resolve("epochs").resolve(base + ".txt");
            check(!epochPath.toString().contains("example.private.test"),
                    "file names must not leak server host");
            check(!Files.readString(epochPath).contains("example.private.test"),
                    "file values must not leak server host");

            Files.writeString(epochPath, "CORRUPTED!");
            try {
                WorldEpochStore.load(temp, base);
                throw new AssertionError("corrupt epoch must not silently recover");
            } catch (IOException expected) {
                // 损坏时 fail closed，而不是重新混合旧地图的数据。
            }
            try {
                WorldEpochStore.effectiveFingerprint(key, rawSource, "bad-epoch");
                throw new AssertionError("invalid scope token rejected");
            } catch (IllegalArgumentException expected) {
                // 无效的 token 不允许参与世界签名。
            }
        } finally {
            try (var walk = Files.walk(temp)) {
                for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        System.out.println("WorldSeedLab epoch isolation smoke checks passed.");
    }

    private static void check(boolean good, String message) {
        if (!good) throw new AssertionError(message);
    }
}
