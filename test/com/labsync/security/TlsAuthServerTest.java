package com.labsync.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import com.labsync.client.AuthClient;
import com.labsync.client.AuthException;
import com.labsync.security.RoleGuard.Role;
import com.labsync.security.TokenService.Claims;

/**
 * Integration tests for the TLS auth server on a real socket, including Member 5's AuthClient.
 * Creates a throw-away keystore/truststore with keytool.
 * Run: java -cp bin;bin/test com.labsync.security.TlsAuthServerTest
 */
public final class TlsAuthServerTest {

    private static int passed;
    private static int failed;

    // Non-ASCII characters are built from code points so this file stays pure ASCII and compiles
    // identically under any javac -encoding (JDK 17 on Windows defaults to windows-1252).
    private static final String E_ACUTE = String.valueOf((char) 0xE9);      // e-acute, 2 bytes in UTF-8
    private static final String CHECK_MARK = String.valueOf((char) 0x2713); // check mark, 3 bytes in UTF-8
    private static final String UNICODE_USER = E_ACUTE + "l" + (char) 0xE8 + "ve";               // "eleve" accented
    private static final String UNICODE_PASSWORD = "contrase" + (char) 0xF1 + "a" + CHECK_MARK;  // n-tilde + check
    private static final long SMALL_DEADLINE_MS = 1000;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("labsync-tls-test");
        Path serverKs = dir.resolve("server.jks");
        Path trustKs = dir.resolve("truststore.jks");
        createCertificates(dir, serverKs, trustKs);

        // Must be set before the first SSLSocketFactory.getDefault() call (AuthClient uses it).
        System.setProperty("javax.net.ssl.trustStore", trustKs.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");

        TokenService tokens = new TokenService(SharedSecret.of("tls-test-secret-0123456789".getBytes(StandardCharsets.UTF_8)));
        TlsAuthServer.UserDirectory users = TlsAuthServer.UserDirectory.demoUsers();
        users.add(UNICODE_USER, Role.STUDENT, UNICODE_PASSWORD.toCharArray());
        javax.net.ssl.SSLContext ctx = TlsAuthServer.serverContext(serverKs, "changeit".toCharArray());
        TlsAuthServer server = new TlsAuthServer(0, ctx, tokens, users);
        server.start();
        int port = server.port();
        // A deliberately tiny server (1 worker, queue 1, 1 s deadline) to test saturation and recovery.
        TlsAuthServer small = new TlsAuthServer(0, ctx, tokens, TlsAuthServer.UserDirectory.demoUsers(),
                java.time.Clock.systemUTC(), 1, 1, java.time.Duration.ofMillis(SMALL_DEADLINE_MS));
        small.start();
        try {
            testStudentLogin(port, tokens);
            testAdminLoginViaClient(port, tokens);
            testWrongPassword(port);
            testUnknownUser(port);
            testPlaintextRejected(port);
            testModernTlsOnly(port);
            testLockout(port);
            testMalformedFrames(port);
            // --- regression tests for the worker-exhaustion and UTF-8 frame-limit fixes
            testOneLoginPerConnection(port);
            testEightIdleConnections(port);
            testSaturationAndRecovery(small.port());
            testTricklingClientIsCut(small.port());
            testFrameLimitInBytes(port);
            testUnicodeLogin(port, tokens);
            testMalformedUtf8(port);
            testRecoveryAfterBadConnections(port);
        } finally {
            small.stop();
            small.awaitTermination();
            server.stop();
            server.awaitTermination();
            for (Path p : List.of(serverKs, trustKs)) {
                Files.deleteIfExists(p);
            }
            Files.deleteIfExists(dir.resolve("server.cer"));
            Files.deleteIfExists(dir);
        }

        System.out.println();
        System.out.printf("RESULT: %d PASS, %d FAIL%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testStudentLogin(int port, TokenService tokens) throws Exception {
        String reply = tlsExchange(port, "LOGIN|student1|pass123");
        String[] p = reply.split("\\|", -1);
        boolean ok = p.length == 4 && p[0].equals("AUTH_OK") && p[1].equals("STUDENT");
        Claims claims = ok ? tokens.verify(p[2], p[3]) : null;
        check("1. LOGIN student1 over TLS -> AUTH_OK|STUDENT|TOKEN|SIG, token verifies",
                ok && claims.userId().equals("student1") && claims.role() == Role.STUDENT
                        && p[3].matches("[0-9a-f]{64}"));
    }

    private static void testAdminLoginViaClient(int port, TokenService tokens) throws Exception {
        AuthClient.LoginResult result = AuthClient.login(InetAddress.getLoopbackAddress(), port, "admin", "admin123");
        Claims claims = tokens.verify(result.session().token(), result.session().signature());
        check("2. Member 5's AuthClient logs in unchanged (admin -> ADMIN session)",
                result.session().isAdmin() && claims.role() == Role.ADMIN
                        && result.tlsProtocol().startsWith("TLSv1."));
        System.out.println("      negotiated " + result.tlsProtocol() + " / " + result.cipherSuite());
    }

    private static void testWrongPassword(int port) throws Exception {
        check("3. Wrong password -> AUTH_FAIL|INVALID_CREDENTIALS",
                tlsExchange(port, "LOGIN|student2|wrong").equals("AUTH_FAIL|INVALID_CREDENTIALS"));
        boolean clientThrows;
        try {
            AuthClient.login(InetAddress.getLoopbackAddress(), port, "student2", "nope");
            clientThrows = false;
        } catch (AuthException expected) {
            clientThrows = true;
        }
        check("4. AuthClient raises AuthException for a failed login", clientThrows);
    }

    private static void testUnknownUser(int port) throws Exception {
        check("5. Unknown user gets the same reply as a wrong password",
                tlsExchange(port, "LOGIN|nobody|pass123").equals("AUTH_FAIL|INVALID_CREDENTIALS"));
    }

    private static void testPlaintextRejected(int port) {
        boolean noCredentialsAccepted;
        try (Socket plain = new Socket("127.0.0.1", port)) {
            plain.setSoTimeout(3000);
            plain.getOutputStream().write("LOGIN|student1|pass123\n".getBytes(StandardCharsets.UTF_8));
            String line = new BufferedReader(new InputStreamReader(plain.getInputStream(), StandardCharsets.UTF_8)).readLine();
            noCredentialsAccepted = line == null || !line.startsWith("AUTH_OK");
        } catch (IOException e) {
            noCredentialsAccepted = true; // connection reset by the TLS layer
        }
        check("6. Plaintext (non-TLS) LOGIN is refused - no AUTH_OK without TLS", noCredentialsAccepted);
    }

    private static void testModernTlsOnly(int port) {
        boolean refused;
        try (SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket("127.0.0.1", port)) {
            s.setSoTimeout(3000);
            s.setEnabledProtocols(new String[] {"TLSv1.1"});
            s.startHandshake();
            refused = false;
        } catch (IOException | IllegalArgumentException e) {
            refused = true;
        }
        check("7. Legacy TLSv1.1 handshake is refused", refused);
    }

    private static void testLockout(int port) throws Exception {
        for (int i = 0; i < TlsAuthServer.MAX_FAILURES; i++) {
            tlsExchange(port, "LOGIN|student2|wrong" + i);
        }
        check("8. After 5 wrong passwords even the right one gets AUTH_FAIL|TOO_MANY_ATTEMPTS",
                tlsExchange(port, "LOGIN|student2|pass123").equals("AUTH_FAIL|TOO_MANY_ATTEMPTS"));
    }

    private static void testMalformedFrames(int port) throws Exception {
        check("9. Malformed frames are rejected cleanly",
                tlsExchange(port, "HELLO").equals("ERR|UNKNOWN_COMMAND")
                        && tlsExchange(port, "LOGIN|student1").equals("AUTH_FAIL|INVALID_CREDENTIALS")
                        && tlsExchange(port, "LOGIN|" + "x".repeat(600)).equals("ERR|FRAME_TOO_LONG"));
    }

    // ------------------------------------------- worker-exhaustion fix (one LOGIN, deadline, bounded queue)

    private static void testOneLoginPerConnection(int port) throws Exception {
        try (SSLSocket s = tls(port, 5000)) {
            // Two requests in one write: only the first may be answered.
            s.getOutputStream().write("LOGIN|student1|pass123\nLOGIN|admin|admin123\n".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            BufferedReader in = reader(s);
            String first = in.readLine();
            String second = readOrNull(in);
            check("10. One LOGIN per connection: first answered, then the server closes (second ignored)",
                    first != null && first.startsWith("AUTH_OK|STUDENT|") && second == null);
        }
    }

    private static void testEightIdleConnections(int port) throws Exception {
        List<Socket> idle = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {           // finished the TLS handshake, never send LOGIN
                SSLSocket s = tls(port, 5000);
                s.startHandshake();
                idle.add(s);
            }
            for (int i = 0; i < 4; i++) {           // plain TCP connections that never start TLS
                idle.add(new Socket("127.0.0.1", port));
            }
            Thread.sleep(300);
            long t0 = System.nanoTime();
            AuthClient.LoginResult r = AuthClient.login(InetAddress.getLoopbackAddress(), port, "admin", "admin123");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check("11. Eight idle connections do not block a real login (AuthClient OK in " + ms + " ms)",
                    r.session().isAdmin() && ms < 2000);
        } finally {
            for (Socket s : idle) {
                s.close();
            }
        }
    }

    private static void testSaturationAndRecovery(int smallPort) throws Exception {
        // 1 worker, queue 1: hogA takes the worker, hogB waits in the queue, hogC is refused.
        try (Socket hogA = new Socket("127.0.0.1", smallPort)) {
            Thread.sleep(150);
            try (Socket hogB = new Socket("127.0.0.1", smallPort)) {
                Thread.sleep(150);
                try (Socket hogC = new Socket("127.0.0.1", smallPort)) {
                    long refusedMs = msUntilClosed(hogC, 3000);
                    check("12. Bounded queue: a connection beyond workers+queue is closed at once (" + refusedMs + " ms)",
                            refusedMs >= 0 && refusedMs < 500);
                }
                long t0 = System.nanoTime();
                long hogAms = msUntilClosed(hogA, 5000);
                long totalA = (System.nanoTime() - t0) / 1_000_000 + 300;
                long hogBms = msUntilClosed(hogB, 5000);
                check("13. Overall deadline: an idle connection is closed after ~" + SMALL_DEADLINE_MS
                                + " ms (closed at ~" + totalA + " ms), queued one released too",
                        hogAms >= 0 && totalA >= SMALL_DEADLINE_MS - 100 && totalA < SMALL_DEADLINE_MS + 1500
                                && hogBms >= 0);
            }
        }
        String after = tlsExchange(smallPort, "LOGIN|student1|pass123");
        check("14. Server recovers after saturation: the next login succeeds",
                after != null && after.startsWith("AUTH_OK|STUDENT|"));
    }

    private static void testTricklingClientIsCut(int smallPort) throws Exception {
        // Sends a TLS record header announcing 200 bytes, then 1 byte every 150 ms, so each single
        // read succeeds. Only the overall deadline (watchdog) can stop it.
        try (Socket s = new Socket("127.0.0.1", smallPort)) {
            OutputStream out = s.getOutputStream();
            out.write(new byte[] {0x16, 0x03, 0x01, 0x00, (byte) 0xC8});
            out.flush();
            Thread writer = new Thread(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        Thread.sleep(150);
                        out.write(0x01);
                        out.flush();
                    }
                } catch (Exception closed) {
                    // the server cut the connection - expected
                }
            });
            writer.setDaemon(true);
            long t0 = System.nanoTime();
            writer.start();
            long ms = msUntilClosed(s, 10_000);
            long total = (System.nanoTime() - t0) / 1_000_000;
            check("15. A client trickling bytes is cut by the overall deadline (" + total + " ms, limit "
                            + SMALL_DEADLINE_MS + " ms + grace)",
                    ms >= 0 && total < SMALL_DEADLINE_MS + 1500);
        }
    }

    // ---------------------------------------------------------------- UTF-8 frame-limit fix

    private static void testFrameLimitInBytes(int port) throws Exception {
        String prefix = "LOGIN|student1|";                        // 15 bytes
        String exact = prefix + E_ACUTE.repeat(248) + "a";       // 15 + 496 + 1 = 512 bytes, 264 chars
        String over = exact + "a";                                // 513 bytes
        String multibyteBig = prefix + E_ACUTE.repeat(300);       // 615 bytes but only 315 chars
        check("16. Limit is in UTF-8 bytes: 512-byte multibyte frame accepted, 513 bytes rejected",
                exact.getBytes(StandardCharsets.UTF_8).length == TlsAuthServer.MAX_FRAME_BYTES
                        && tlsExchange(port, exact).equals("AUTH_FAIL|INVALID_CREDENTIALS")
                        && tlsExchange(port, over).equals("ERR|FRAME_TOO_LONG"));
        check("17. Oversized frames rejected whole: 600 ASCII bytes and 300 x e-acute (315 chars, 615 bytes)",
                tlsExchange(port, prefix + "x".repeat(600)).equals("ERR|FRAME_TOO_LONG")
                        && tlsExchange(port, multibyteBig).equals("ERR|FRAME_TOO_LONG"));

        // The tail of an oversized frame must never be read as a second request.
        try (SSLSocket s = tls(port, 5000)) {
            String smuggle = prefix + "x".repeat(600) + "LOGIN|admin|admin123\n";
            s.getOutputStream().write(smuggle.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            BufferedReader in = reader(s);
            String first = in.readLine();
            String second = readOrNull(in);
            check("18. Tail of an oversized frame is discarded, not run as a request; connection closed",
                    "ERR|FRAME_TOO_LONG".equals(first) && second == null);
        }
    }

    private static void testUnicodeLogin(int port, TokenService tokens) throws Exception {
        String reply = tlsExchange(port, "LOGIN|" + UNICODE_USER + "|" + UNICODE_PASSWORD);
        String[] p = reply.split("\\|", -1);
        Claims claims = p.length == 4 && p[0].equals("AUTH_OK") ? tokens.verify(p[2], p[3]) : null;
        check("19. Valid UTF-8 frame handled correctly (accented user, password with n-tilde and check mark)",
                claims != null && claims.userId().equals(UNICODE_USER)
                        && tlsExchange(port, "LOGIN|" + UNICODE_USER + "|contrasena" + CHECK_MARK)
                                .equals("AUTH_FAIL|INVALID_CREDENTIALS"));
    }

    private static void testMalformedUtf8(int port) throws Exception {
        try (SSLSocket s = tls(port, 5000)) {
            OutputStream out = s.getOutputStream();
            out.write("LOGIN|student1|".getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[] {(byte) 0xC3, 0x28, '\n'});      // invalid 2-byte sequence
            out.flush();
            check("20. Invalid UTF-8 bytes -> ERR|MALFORMED_FRAME (not silently replaced)",
                    "ERR|MALFORMED_FRAME".equals(reader(s).readLine()));
        }
    }

    private static void testRecoveryAfterBadConnections(int port) throws Exception {
        for (int i = 0; i < 5; i++) {
            try (Socket s = new Socket("127.0.0.1", port)) {          // garbage instead of TLS
                s.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            try (SSLSocket s = tls(port, 5000)) {                     // TLS, then disconnect mid-frame
                s.getOutputStream().write("LOGIN|stud".getBytes(StandardCharsets.US_ASCII));
                s.getOutputStream().flush();
            }
        }
        check("21. After 10 malformed/aborted connections the server still logs users in",
                tlsExchange(port, "LOGIN|admin|admin123").startsWith("AUTH_OK|ADMIN|"));
    }

    // ------------------------------------------------------------------------------- helpers

    private static SSLSocket tls(int port, int timeoutMs) throws IOException {
        SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket("127.0.0.1", port);
        s.setSoTimeout(timeoutMs);
        return s;
    }

    private static BufferedReader reader(Socket s) throws IOException {
        return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    /** Next line, or null if the server closed the connection (EOF or reset). */
    private static String readOrNull(BufferedReader in) {
        try {
            return in.readLine();
        } catch (IOException closed) {
            return null;
        }
    }

    /** Blocks until the peer closes {@code s}; returns elapsed ms, or -1 if still open at the timeout. */
    private static long msUntilClosed(Socket s, int timeoutMs) throws IOException {
        s.setSoTimeout(timeoutMs);
        long t0 = System.nanoTime();
        try {
            while (s.getInputStream().read() != -1) {
                // discard anything the server sends
            }
        } catch (java.net.SocketTimeoutException stillOpen) {
            return -1;
        } catch (IOException reset) {
            // connection reset also means closed
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static String tlsExchange(int port, String frame) throws IOException {
        try (SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            out.write((frame + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    static void createCertificates(Path dir, Path serverKs, Path trustKs) throws Exception {
        String keytool = Paths.get(System.getProperty("java.home"), "bin", "keytool").toString();
        Path cer = dir.resolve("server.cer");
        run(keytool, "-genkeypair", "-alias", "labsync", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore", serverKs.toString(), "-storepass", "changeit", "-keypass", "changeit");
        run(keytool, "-exportcert", "-alias", "labsync", "-keystore", serverKs.toString(),
                "-storepass", "changeit", "-file", cer.toString());
        run(keytool, "-importcert", "-noprompt", "-alias", "labsync", "-file", cer.toString(),
                "-keystore", trustKs.toString(), "-storepass", "changeit");
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
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
