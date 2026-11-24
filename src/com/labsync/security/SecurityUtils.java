package com.labsync.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic helpers shared by the auth server (port 8443) and the NIO gateway (port 5000).
 *
 * <p>Everything here is plain JDK ({@code java.security} / {@code javax.crypto}):
 * <ul>
 *   <li>{@link #sha256Hex} - a SHA-256 {@link MessageDigest} (fingerprints, checksums);</li>
 *   <li>{@link #hmacSha256} - a keyed SHA-256 signature. A plain {@code SHA-256(data)} can be
 *       recomputed by anyone, so it cannot prove who produced the data; HMAC mixes in a server
 *       secret, so only a server holding that secret can produce a matching signature;</li>
 *   <li>{@link #constantTimeEquals} - signature comparison whose running time does not depend on
 *       where the first wrong character is, so an attacker cannot guess a signature byte by byte
 *       by timing the server;</li>
 *   <li>{@link #hashPassword} / {@link #verifyPassword} - salted, slow password hashing
 *       (PBKDF2 with HMAC-SHA256), so a leaked user table does not reveal passwords.</li>
 * </ul>
 */
public final class SecurityUtils {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    static final int PASSWORD_ITERATIONS = 65_536;
    private static final int PASSWORD_SALT_BYTES = 16;
    private static final int PASSWORD_HASH_BITS = 256;

    private SecurityUtils() { }

    // ------------------------------------------------------------------------------ digests

    /** SHA-256 of the UTF-8 bytes of {@code text}, as 64 lowercase hex characters. */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] data) {
        return HEX.formatHex(sha256(data));
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    // ---------------------------------------------------------------------------- signatures

    /** HMAC-SHA256 of {@code data} under {@code key} (32 raw bytes). */
    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); // Mac is not thread-safe: one per call
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is mandatory in every JDK", e);
        }
    }

    /** HMAC-SHA256 of the UTF-8 bytes of {@code text}, as 64 lowercase hex characters. */
    public static String hmacSha256Hex(byte[] key, String text) {
        return HEX.formatHex(hmacSha256(key, text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Compares two strings in constant time with {@link MessageDigest#isEqual}, which always walks
     * the full length instead of returning at the first mismatch. {@code null} never matches.
     */
    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    // ----------------------------------------------------------------------------- passwords

    /** Returns {@code "<saltHex>:<hashHex>"} for storage. A fresh random salt is used each call. */
    public static String hashPassword(char[] password) {
        byte[] salt = randomBytes(PASSWORD_SALT_BYTES);
        return HEX.formatHex(salt) + ":" + HEX.formatHex(pbkdf2(password, salt));
    }

    /** Checks a password against a {@link #hashPassword} value, comparing in constant time. */
    public static boolean verifyPassword(char[] password, String stored) {
        int colon = stored.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        byte[] salt = HEX.parseHex(stored, 0, colon);
        byte[] expected = HEX.parseHex(stored, colon + 1, stored.length());
        return MessageDigest.isEqual(expected, pbkdf2(password, salt));
    }

    private static byte[] pbkdf2(char[] password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(password, salt, PASSWORD_ITERATIONS, PASSWORD_HASH_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is mandatory in every JDK", e);
        }
    }

    // --------------------------------------------------------------------------------- misc

    public static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static String toHex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    /** Prints a storable hash for a password: {@code java -cp bin com.labsync.security.SecurityUtils <pw>}. */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("usage: java -cp bin com.labsync.security.SecurityUtils <password>");
            return;
        }
        System.out.println(hashPassword(args[0].toCharArray()));
    }
}
