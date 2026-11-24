package com.labsync.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import com.labsync.security.AuthorizationException.Reason;
import com.labsync.security.RoleGuard.Action;
import com.labsync.security.RoleGuard.Role;
import com.labsync.security.TokenService.Claims;
import com.labsync.security.TokenService.IssuedToken;

/**
 * Unit tests for SecurityUtils, SharedSecret, TokenService and RoleGuard.
 * Run: java -cp bin;bin/test com.labsync.security.TokenSecurityTest
 */
public final class TokenSecurityTest {

    private static int passed;
    private static int failed;

    private static final SharedSecret SECRET =
            SharedSecret.of("unit-test-secret-0123456789".getBytes(StandardCharsets.UTF_8));

    public static void main(String[] args) throws Exception {
        testSha256KnownVector();
        testHmacKnownVector();
        testConstantTimeEquals();
        testPasswordHashing();
        testIssueAndVerify();
        testTokenIsEncrypted();
        testTamperedTokenRejected();
        testTamperedSignatureRejected();
        testForgedTokenWithValidSignatureRejected();
        testExpiry();
        testSameSecretInteroperates();
        testDifferentSecretRejected();
        testSharedSecretFile();
        testRoleGuard();
        testReceiptSignature();

        System.out.println();
        System.out.printf("RESULT: %d PASS, %d FAIL%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testSha256KnownVector() {
        check("1. SHA-256 matches the FIPS 180-2 test vector for \"abc\"",
                SecurityUtils.sha256Hex("abc").equals(
                        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
    }

    private static void testHmacKnownVector() {
        // RFC 4231 test case 2
        String mac = SecurityUtils.hmacSha256Hex("Jefe".getBytes(StandardCharsets.UTF_8),
                "what do ya want for nothing?");
        check("2. HMAC-SHA256 matches RFC 4231 test case 2",
                mac.equals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"));
    }

    private static void testConstantTimeEquals() {
        check("3. constantTimeEquals: equal, unequal, different length, null",
                SecurityUtils.constantTimeEquals("abcd", "abcd")
                        && !SecurityUtils.constantTimeEquals("abcd", "abce")
                        && !SecurityUtils.constantTimeEquals("abcd", "abc")
                        && !SecurityUtils.constantTimeEquals("abcd", null));
    }

    private static void testPasswordHashing() {
        String stored = SecurityUtils.hashPassword("pass123".toCharArray());
        String again = SecurityUtils.hashPassword("pass123".toCharArray());
        check("4. Password hash is salted and verifies only the right password",
                SecurityUtils.verifyPassword("pass123".toCharArray(), stored)
                        && !SecurityUtils.verifyPassword("pass124".toCharArray(), stored)
                        && !stored.equals(again)
                        && !stored.contains("pass123"));
    }

    private static void testIssueAndVerify() throws Exception {
        TokenService tokens = new TokenService(SECRET);
        IssuedToken issued = tokens.issue("student1", Role.STUDENT);
        Claims claims = tokens.verify(issued.token(), issued.signature());
        check("5. Issued token verifies and carries userId + role",
                claims.userId().equals("student1") && claims.role() == Role.STUDENT
                        && issued.signature().matches("[0-9a-f]{64}")
                        && !issued.token().contains("|") && !issued.token().contains("\n"));
    }

    private static void testTokenIsEncrypted() {
        TokenService tokens = new TokenService(SECRET);
        IssuedToken a = tokens.issue("student1", Role.STUDENT);
        IssuedToken b = tokens.issue("student1", Role.STUDENT);
        String decoded = new String(Base64.getUrlDecoder().decode(a.token()), StandardCharsets.ISO_8859_1);
        check("6. Token is AES-GCM ciphertext: no plaintext user/role, random IV per token",
                !decoded.contains("student1") && !decoded.contains("STUDENT")
                        && !a.token().equals(b.token()));
    }

    private static void testTamperedTokenRejected() {
        TokenService tokens = new TokenService(SECRET);
        IssuedToken issued = tokens.issue("student1", Role.STUDENT);
        String tampered = flipChar(issued.token(), issued.token().length() / 2);
        check("7. One changed character in the token -> INVALID_SIGNATURE",
                reasonOf(() -> tokens.verify(tampered, issued.signature())) == Reason.INVALID_SIGNATURE);
    }

    private static void testTamperedSignatureRejected() {
        TokenService tokens = new TokenService(SECRET);
        IssuedToken issued = tokens.issue("student1", Role.STUDENT);
        String badSig = flipChar(issued.signature(), 0);
        check("8. One changed character in the signature -> INVALID_SIGNATURE; empty -> MISSING_TOKEN",
                reasonOf(() -> tokens.verify(issued.token(), badSig)) == Reason.INVALID_SIGNATURE
                        && reasonOf(() -> tokens.verify(issued.token(), "")) == Reason.MISSING_TOKEN);
    }

    private static void testForgedTokenWithValidSignatureRejected() {
        // Even if someone could sign arbitrary bytes, they still cannot produce valid AES-GCM ciphertext.
        TokenService tokens = new TokenService(SECRET);
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v1|admin|ADMIN|0|99999999999".getBytes(StandardCharsets.UTF_8));
        check("9. Correctly signed but non-encrypted token -> MALFORMED_TOKEN",
                reasonOf(() -> tokens.verify(forged, tokens.sign(forged))) == Reason.MALFORMED_TOKEN);
    }

    private static void testExpiry() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-24T10:00:00Z"));
        TokenService tokens = new TokenService(SECRET, Duration.ofMinutes(30), clock);
        IssuedToken issued = tokens.issue("student1", Role.STUDENT);

        clock.now = Instant.parse("2026-11-24T10:29:59Z");
        boolean validBefore = tokens.verify(issued.token(), issued.signature()) != null;
        clock.now = Instant.parse("2026-11-24T10:30:00Z");
        Reason atExpiry = reasonOf(() -> tokens.verify(issued.token(), issued.signature()));
        check("10. Token valid 1 s before expiry, TOKEN_EXPIRED at expiry",
                validBefore && atExpiry == Reason.TOKEN_EXPIRED
                        && issued.claims().expiresAt().equals(Instant.parse("2026-11-24T10:30:00Z")));
    }

    private static void testSameSecretInteroperates() throws Exception {
        // Simulates two processes (auth server + gateway) that loaded the same secret.
        TokenService authServer = new TokenService(SharedSecret.of("shared-between-servers!".getBytes(StandardCharsets.UTF_8)));
        TokenService gateway = new TokenService(SharedSecret.of("shared-between-servers!".getBytes(StandardCharsets.UTF_8)));
        IssuedToken issued = authServer.issue("admin", Role.ADMIN);
        Claims claims = gateway.verify(issued.token(), issued.signature());
        check("11. Token issued by one TokenService verifies on another with the same secret",
                claims.userId().equals("admin") && claims.role() == Role.ADMIN
                        && authServer.fingerprint().equals(gateway.fingerprint()));
    }

    private static void testDifferentSecretRejected() {
        TokenService authServer = new TokenService(SharedSecret.of("secret-number-one-xx".getBytes(StandardCharsets.UTF_8)));
        TokenService gateway = new TokenService(SharedSecret.of("secret-number-two-xx".getBytes(StandardCharsets.UTF_8)));
        IssuedToken issued = authServer.issue("admin", Role.ADMIN);
        check("12. Token from a server with a different secret -> INVALID_SIGNATURE",
                reasonOf(() -> gateway.verify(issued.token(), issued.signature())) == Reason.INVALID_SIGNATURE
                        && !authServer.fingerprint().equals(gateway.fingerprint()));
    }

    private static void testSharedSecretFile() throws Exception {
        Path dir = Files.createTempDirectory("labsync-secret-test");
        Path file = dir.resolve("labsync.secret");
        String oldProp = System.getProperty(SharedSecret.FILE_PROPERTY);
        boolean envSet = System.getenv(SharedSecret.ENV) != null;
        try {
            System.clearProperty(SharedSecret.PROPERTY);
            System.setProperty(SharedSecret.FILE_PROPERTY, file.toString());
            SharedSecret first = SharedSecret.load();   // creates the file
            SharedSecret second = SharedSecret.load();  // reads the same file
            check("13. Secret file is created once and re-read (same fingerprint)",
                    envSet || (Files.exists(file) && first.fingerprint().equals(second.fingerprint())));

            System.setProperty(SharedSecret.PROPERTY, "a-property-secret-123");
            SharedSecret fromProp = SharedSecret.load();
            check("14. -Dlabsync.secret takes priority over the file",
                    fromProp.source().contains(SharedSecret.PROPERTY)
                            && !fromProp.fingerprint().equals(first.fingerprint()));
            System.setProperty(SharedSecret.PROPERTY, "short");
            boolean rejected;
            try {
                SharedSecret.load();
                rejected = false;
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check("15. A secret shorter than 16 characters is refused", rejected);
        } finally {
            System.clearProperty(SharedSecret.PROPERTY);
            if (oldProp == null) {
                System.clearProperty(SharedSecret.FILE_PROPERTY);
            } else {
                System.setProperty(SharedSecret.FILE_PROPERTY, oldProp);
            }
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    private static void testRoleGuard() throws Exception {
        TokenService tokens = new TokenService(SECRET);
        RoleGuard guard = new RoleGuard(tokens);
        IssuedToken student = tokens.issue("student1", Role.STUDENT);
        IssuedToken admin = tokens.issue("admin", Role.ADMIN);

        boolean studentBooks = guard.authorize(Action.BOOK, student.token(), student.signature())
                .userId().equals("student1");
        Reason studentAddsLab = reasonOf(() -> guard.authorize(Action.ADD_LAB, student.token(), student.signature()));
        boolean adminAddsLab = guard.authorize(Action.ADD_LAB, admin.token(), admin.signature())
                .role() == Role.ADMIN;
        check("16. RoleGuard: STUDENT may BOOK, STUDENT ADD_LAB -> FORBIDDEN, ADMIN may ADD_LAB",
                studentBooks && studentAddsLab == Reason.FORBIDDEN && adminAddsLab);
        check("17. RoleGuard policy table",
                RoleGuard.permits(Role.ADMIN, Action.BOOK) && RoleGuard.permits(Role.STUDENT, Action.CANCEL)
                        && !RoleGuard.permits(Role.STUDENT, Action.ADD_LAB) && !RoleGuard.permits(null, Action.BOOK));
    }

    private static void testReceiptSignature() {
        TokenService tokens = new TokenService(SECRET);
        Instant ts = Instant.parse("2026-11-24T10:00:00Z");
        String hash = tokens.signReceipt("R1", "LAB_A", "S05", "student1", ts);
        check("18. Receipt hash is 64 hex, verifies, and changes if any field changes",
                hash.matches("[0-9a-f]{64}")
                        && tokens.verifyReceipt(hash, "R1", "LAB_A", "S05", "student1", ts)
                        && !tokens.verifyReceipt(hash, "R1", "LAB_A", "S06", "student1", ts)
                        && !tokens.verifyReceipt(hash, "R1", "LAB_A", "S05", "student2", ts));
    }

    // ------------------------------------------------------------------------------- helpers

    private interface Verification {
        Object run() throws AuthorizationException;
    }

    private static Reason reasonOf(Verification v) {
        try {
            v.run();
            return null;
        } catch (AuthorizationException e) {
            return e.reason();
        }
    }

    static String flipChar(String s, int index) {
        char c = s.charAt(index);
        char replacement = c == 'A' ? 'B' : 'A';
        if (Character.isDigit(c) || (c >= 'a' && c <= 'f')) {
            replacement = c == '0' ? '1' : '0'; // keep hex signatures hex
        }
        return s.substring(0, index) + replacement + s.substring(index + 1);
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS: " + name);
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }
}
