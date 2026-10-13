package io.github.fxjcangku.worldseedlab.core.privacy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Local, keyed fingerprints; do not publish the key or the raw world discriminator.
 * No stable server identity can be reconstructed from the result without the local secret.
 */
public final class WorldFingerprint {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private WorldFingerprint() {
    }

    public static String fromLocalSecret(byte[] key, String discriminator) {
        if (key == null || key.length < 16) {
            throw new IllegalArgumentException("a per-installation secret of at least 16 bytes is required");
        }
        if (discriminator == null || discriminator.isBlank()) {
            throw new IllegalArgumentException("discriminator is required");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return hex(mac.doFinal(discriminator.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static String dimensionLabel(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("dimension is required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(dimension.getBytes(StandardCharsets.UTF_8));
            return hex(digest).substring(0, 24);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int unsigned = bytes[i] & 0xff;
            chars[i * 2] = HEX[unsigned >>> 4];
            chars[i * 2 + 1] = HEX[unsigned & 0x0f];
        }
        return new String(chars);
    }
}

