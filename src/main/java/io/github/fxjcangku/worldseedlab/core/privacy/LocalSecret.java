package io.github.fxjcangku.worldseedlab.core.privacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Objects;

/** Per-installation random secret; stored only in the local config directory. */
public final class LocalSecret {
    private LocalSecret() {
    }

    public static byte[] loadOrCreate(Path directory) throws IOException {
        Objects.requireNonNull(directory);
        Files.createDirectories(directory);
        Path file = directory.resolve("identity.key");
        if (Files.isSymbolicLink(file)) {
            throw new IOException("refusing symbolic-link identity key");
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] existing = Files.readAllBytes(file);
            if (existing.length != 32) {
                throw new IOException("corrupted identity key, refusing to re-identify worlds");
            }
            return existing;
        }
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        Files.write(file, secret, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return secret;
    }
}
