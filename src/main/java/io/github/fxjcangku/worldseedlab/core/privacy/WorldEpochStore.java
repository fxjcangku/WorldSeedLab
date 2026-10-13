package io.github.fxjcangku.worldseedlab.core.privacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 服务器同地址改图/重置后的本地世界纪元保护。
 *
 * <p>普通客户端无法证明世界是否已重置，只能让用户显式创建新纪元。
 * 文件名使用已有本机 HMAC 指纹，不保存服务器 IP、名称或任何服务端 Seed。
 * 没有历史配置的世界使用 legacy，确保已有证据目录不发生静默迁移。
 */
public final class WorldEpochStore {
    private static final Pattern WORLD_KEY = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern EPOCH = Pattern.compile("[a-f0-9-]{36}");
    public static final String LEGACY = "legacy";

    private WorldEpochStore() {
    }

    public static String load(Path configRoot, String baseFingerprint) throws IOException {
        Path file = file(configRoot, baseFingerprint);
        if (!Files.exists(file)) {
            return LEGACY;
        }
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)
                || Files.size(file) > 64) {
            throw new IOException("世界纪元文件异常，已拒绝不安全恢复");
        }
        String value = Files.readString(file, StandardCharsets.US_ASCII).strip();
        if (!valid(value)) {
            throw new IOException("世界纪元标识损坏，拒绝把不同地图混合");
        }
        return value;
    }

    /** 此操作不删除旧数据，只改变以后采集使用的本机身份指纹。 */
    public static String newEpoch(Path configRoot, String baseFingerprint) throws IOException {
        Path file = file(configRoot, baseFingerprint);
        Files.createDirectories(file.getParent());
        if (Files.isSymbolicLink(file)) {
            throw new IOException("拒绝向符号链接纪元文件写入");
        }
        String token = UUID.randomUUID().toString();
        Path temporary = Files.createTempFile(file.getParent(), "epoch-", ".tmp");
        try {
            Files.writeString(temporary, token + "\n", StandardCharsets.US_ASCII,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return token;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static String effectiveFingerprint(byte[] secret, String source, String token) {
        if (!valid(token)) {
            throw new IllegalArgumentException("unrecognized local world epoch");
        }
        return WorldFingerprint.fromLocalSecret(secret, LEGACY.equals(token)
                ? source : source + "|worldseedlab-epoch=" + token);
    }

    private static boolean valid(String token) {
        if (LEGACY.equals(token)) return true;
        if (token == null || !EPOCH.matcher(token).matches()) return false;
        try {
            return UUID.fromString(token).toString().equals(token);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static Path file(Path root, String fingerprint) {
        Objects.requireNonNull(root);
        if (fingerprint == null || !WORLD_KEY.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("invalid local world key");
        }
        Path base = root.toAbsolutePath().normalize();
        Path path = base.resolve("epochs").resolve(fingerprint + ".txt").normalize();
        if (!path.startsWith(base)) {
            throw new IllegalArgumentException("epoch path escapes configuration root");
        }
        return path;
    }
}
