package com.labsync.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.labsync.security.AuthorizationException.Reason;
import com.labsync.security.RoleGuard.Role;

/**
 * Issues and verifies the session tokens that link the TLS login (port 8443) to the NIO gateway
 * (port 5000).
 *
 * <pre>
 *   claims    = "v1|userId|ROLE|issuedAtEpochSec|expiresAtEpochSec"
 *   TOKEN     = base64url( iv(12 bytes) || AES-256-GCM(claims) )     - nobody can read or forge it
 *   SIGNATURE = hex( HMAC-SHA256(signKey, TOKEN) )                  - 64 hex chars
 *
 *   wire: AUTH_OK|ROLE|TOKEN|SIGNATURE   then   BOOK|lab|seat|TOKEN|SIGNATURE
 * </pre>
 *
 * <p>Verification order: (1) recompute the HMAC and compare in constant time, so a token with even
 * one changed character is rejected before any decryption work; (2) AES-GCM decrypt, which also
 * checks GCM's own authentication tag; (3) check the expiry time.
 *
 * <p>Three independent keys are derived from the {@link SharedSecret}: AES for encrypting tokens,
 * HMAC for signing tokens, and HMAC for signing booking receipts. Instances are immutable and
 * thread-safe.
 */
public final class TokenService {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    public static final String TTL_PROPERTY = "labsync.tokenTtlSeconds";

    private static final String VERSION = "v1";
    private static final int IV_BYTES = 12;     // the size NIST recommends for GCM
    private static final int TAG_BITS = 128;
    private static final byte[] AAD = "labsync-token-v1".getBytes(StandardCharsets.UTF_8);

    /** What a verified token says about its holder. */
    public record Claims(String userId, Role role, Instant issuedAt, Instant expiresAt) { }

    /** A freshly issued token and its signature, ready for the AUTH_OK frame. */
    public record IssuedToken(String token, String signature, Claims claims) { }

    private final SecretKey aesKey;
    private final byte[] tokenSignKey;
    private final byte[] receiptSignKey;
    private final String fingerprint;
    private final Duration ttl;
    private final Clock clock;

    public TokenService(SharedSecret secret, Duration ttl, Clock clock) {
        Objects.requireNonNull(secret, "secret");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.aesKey = new SecretKeySpec(secret.deriveKey("token-aes"), "AES");
        this.tokenSignKey = secret.deriveKey("token-hmac");
        this.receiptSignKey = secret.deriveKey("receipt-hmac");
        this.fingerprint = secret.fingerprint();
        this.ttl = ttl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TokenService(SharedSecret secret) {
        this(secret, DEFAULT_TTL, Clock.systemUTC());
    }

    /**
     * The service both real servers use: secret from {@link SharedSecret#load()}, lifetime from
     * {@code -Dlabsync.tokenTtlSeconds} (default 30 minutes).
     */
    public static TokenService fromEnvironment() {
        long ttlSeconds = Long.getLong(TTL_PROPERTY, DEFAULT_TTL.toSeconds());
        SharedSecret secret = SharedSecret.load();
        TokenService service = new TokenService(secret, Duration.ofSeconds(ttlSeconds), Clock.systemUTC());
        System.out.printf("[Security] shared secret loaded from %s, fingerprint %s, token lifetime %ds%n",
                secret.source(), secret.fingerprint(), ttlSeconds);
        return service;
    }

    // -------------------------------------------------------------------------------- tokens

    public IssuedToken issue(String userId, Role role) {
        if (userId == null || userId.isBlank() || userId.contains("|")) {
            throw new IllegalArgumentException("invalid userId");
        }
        Instant now = clock.instant();
        Claims claims = new Claims(userId, Objects.requireNonNull(role, "role"), now, now.plus(ttl));
        String plain = String.join("|", VERSION, userId, role.name(),
                Long.toString(claims.issuedAt().getEpochSecond()),
                Long.toString(claims.expiresAt().getEpochSecond()));

        byte[] iv = SecurityUtils.randomBytes(IV_BYTES);
        byte[] cipherText = crypt(Cipher.ENCRYPT_MODE, iv, plain.getBytes(StandardCharsets.UTF_8));
        byte[] packed = ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText).array();

        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        return new IssuedToken(token, sign(token), claims);
    }

    /** The HMAC-SHA256 signature of a token, as 64 hex characters. */
    public String sign(String token) {
        return SecurityUtils.hmacSha256Hex(tokenSignKey, token);
    }

    /**
     * Verifies a token and its signature and returns the claims inside.
     *
     * @throws AuthorizationException MISSING_TOKEN, INVALID_SIGNATURE, MALFORMED_TOKEN or TOKEN_EXPIRED
     */
    public Claims verify(String token, String signature) throws AuthorizationException {
        if (token == null || token.isEmpty() || signature == null || signature.isEmpty()) {
            throw new AuthorizationException(Reason.MISSING_TOKEN, "token or signature missing");
        }
        // Step 1: signature, compared in constant time.
        if (!SecurityUtils.constantTimeEquals(sign(token), signature.toLowerCase())) {
            throw new AuthorizationException(Reason.INVALID_SIGNATURE,
                    "HMAC-SHA256 signature does not match the token");
        }
        // Step 2: decrypt.
        Claims claims = decrypt(token);
        // Step 3: expiry.
        if (!clock.instant().isBefore(claims.expiresAt())) {
            throw new AuthorizationException(Reason.TOKEN_EXPIRED,
                    "token expired at " + claims.expiresAt());
        }
        return claims;
    }

    private Claims decrypt(String token) throws AuthorizationException {
        try {
            byte[] packed = Base64.getUrlDecoder().decode(token);
            if (packed.length <= IV_BYTES) {
                throw new IllegalArgumentException("too short");
            }
            ByteBuffer buf = ByteBuffer.wrap(packed);
            byte[] iv = new byte[IV_BYTES];
            buf.get(iv);
            byte[] cipherText = new byte[buf.remaining()];
            buf.get(cipherText);

            String plain = new String(crypt(Cipher.DECRYPT_MODE, iv, cipherText), StandardCharsets.UTF_8);
            String[] p = plain.split("\\|", -1);
            if (p.length != 5 || !VERSION.equals(p[0])) {
                throw new IllegalArgumentException("bad claims layout");
            }
            return new Claims(p[1], Role.valueOf(p[2]),
                    Instant.ofEpochSecond(Long.parseLong(p[3])), Instant.ofEpochSecond(Long.parseLong(p[4])));
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Base64, layout, enum and number errors; GCM tag failures arrive as IllegalStateException.
            throw new AuthorizationException(Reason.MALFORMED_TOKEN, "token cannot be decrypted: " + e.getMessage());
        }
    }

    private byte[] crypt(int mode, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            return cipher.doFinal(input);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("GCM authentication tag mismatch", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/GCM failure: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------------------ receipts

    /**
     * Tamper-evident receipt hash: HMAC-SHA256 over every receipt field, as 64 hex characters.
     * Changing any field (seat, user, time...) changes the hash, and only a server holding the
     * shared secret can produce it.
     */
    public String signReceipt(String receiptId, String labId, String seatId, String userId, Instant timestamp) {
        String canonical = String.join("|", "receipt-v1", receiptId, labId, seatId, userId,
                Long.toString(timestamp == null ? 0 : timestamp.toEpochMilli()));
        return SecurityUtils.hmacSha256Hex(receiptSignKey, canonical);
    }

    public boolean verifyReceipt(String hash, String receiptId, String labId, String seatId,
            String userId, Instant timestamp) {
        return SecurityUtils.constantTimeEquals(signReceipt(receiptId, labId, seatId, userId, timestamp),
                hash == null ? null : hash.toLowerCase());
    }

    public Duration ttl() {
        return ttl;
    }

    /** Fingerprint of the shared secret this service was built from (safe to log). */
    public String fingerprint() {
        return fingerprint;
    }
}
