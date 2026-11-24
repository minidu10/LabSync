package com.labsync.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * Loads the secret that BOTH servers derive their token keys from.
 *
 * <p>The auth server (port 8443) signs tokens and the gateway (port 5000) verifies them. They are
 * separate processes, so they must load the same secret, otherwise every token from 8443 would be
 * rejected on 5000. The first source found wins:
 * <ol>
 *   <li>system property {@code -Dlabsync.secret=<at least 16 characters>};</li>
 *   <li>environment variable {@code LABSYNC_SECRET};</li>
 *   <li>a secret file (default {@code labsync.secret} in the working directory, or
 *       {@code -Dlabsync.secretFile=<path>}). The first server to start creates it with 32 random
 *       bytes and the second one reads it, so two servers started from the same folder agree.</li>
 * </ol>
 *
 * <p>The secret itself is never printed; {@link #fingerprint} is a short SHA-256 prefix that both
 * servers log, so you can see at a glance that they share the same key.
 */
public final class SharedSecret {

    public static final String PROPERTY = "labsync.secret";
    public static final String ENV = "LABSYNC_SECRET";
    public static final String FILE_PROPERTY = "labsync.secretFile";
    public static final String DEFAULT_FILE = "labsync.secret";
    static final int MIN_LENGTH = 16;

    private final byte[] secret;
    private final String source;

    private SharedSecret(byte[] secret, String source) {
        if (secret.length < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "shared secret from " + source + " must be at least " + MIN_LENGTH + " bytes");
        }
        this.secret = secret.clone();
        this.source = source;
    }

    /** A secret given directly (tests, or a launcher that already holds one). */
    public static SharedSecret of(byte[] secret) {
        return new SharedSecret(secret, "in-memory");
    }

    /** Loads the secret from the property, the environment, or the secret file, in that order. */
    public static SharedSecret load() {
        String value = System.getProperty(PROPERTY);
        if (value != null && !value.isEmpty()) {
            return new SharedSecret(value.getBytes(StandardCharsets.UTF_8), "-D" + PROPERTY);
        }
        value = System.getenv(ENV);
        if (value != null && !value.isEmpty()) {
            return new SharedSecret(value.getBytes(StandardCharsets.UTF_8), "$" + ENV);
        }
        Path file = Paths.get(System.getProperty(FILE_PROPERTY, DEFAULT_FILE)).toAbsolutePath();
        return new SharedSecret(loadOrCreateFile(file), "file " + file);
    }

    private static byte[] loadOrCreateFile(Path file) {
        try {
            if (!Files.exists(file)) {
                String generated = Base64.getEncoder().encodeToString(SecurityUtils.randomBytes(32));
                Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "labsync", ".tmp");
                Files.writeString(temp, generated + System.lineSeparator(), StandardCharsets.UTF_8);
                try {
                    // A plain rename without REPLACE_EXISTING fails if the other server got there
                    // first, so the two servers can never end up with different secrets.
                    Files.move(temp, file);
                } catch (FileAlreadyExistsException raced) {
                    Files.deleteIfExists(temp);
                }
            }
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                throw new IllegalStateException("secret file " + file + " is empty");
            }
            return content.getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read or create secret file " + file, e);
        }
    }

    /** Derives an independent 32-byte key for one purpose, so no key is used for two jobs. */
    byte[] deriveKey(String purpose) {
        return SecurityUtils.hmacSha256(secret, ("labsync-key|" + purpose).getBytes(StandardCharsets.UTF_8));
    }

    /** Where the secret came from (never the secret itself). */
    public String source() {
        return source;
    }

    /** First 16 hex characters of SHA-256(secret): equal on both servers means the same secret. */
    public String fingerprint() {
        return SecurityUtils.sha256Hex(secret).substring(0, 16);
    }
}
