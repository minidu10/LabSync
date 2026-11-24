package com.labsync.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import com.labsync.client.AuthClient;
import com.labsync.client.Session;
import com.labsync.engine.BookingWorkerPool;
import com.labsync.engine.SeatStore;
import com.labsync.nio.NioGatewayServer;
import com.labsync.security.RoleGuard.Role;

/**
 * End-to-end: TLS login on the auth server, then signed requests on the NIO gateway.
 * The auth server and the gateway each build their own TokenService from the same secret bytes,
 * exactly like two separate processes that loaded the same shared secret.
 * Run: java -cp bin;bin/test com.labsync.security.GatewaySecurityTest
 */
public final class GatewaySecurityTest {

    private static int passed;
    private static int failed;
    private static final byte[] SECRET = "end-to-end-shared-secret-42".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("labsync-e2e-test");
        Path serverKs = dir.resolve("server.jks");
        Path trustKs = dir.resolve("truststore.jks");
        TlsAuthServerTest.createCertificates(dir, serverKs, trustKs);
        System.setProperty("javax.net.ssl.trustStore", trustKs.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");

        TlsAuthServer auth = new TlsAuthServer(0, TlsAuthServer.serverContext(serverKs, "changeit".toCharArray()),
                new TokenService(SharedSecret.of(SECRET)), TlsAuthServer.UserDirectory.demoUsers());
        auth.start();

        SeatStore seats = new SeatStore(Map.of("LAB_A", 20, "LAB_B", 20));
        BookingWorkerPool pool = new BookingWorkerPool(4);
        int gwPort = freePort();
        NioGatewayServer gateway = new NioGatewayServer(gwPort, seats, pool,
                new RoleGuard(new TokenService(SharedSecret.of(SECRET))));
        gateway.start();

        try {
            Session student = AuthClient.login(InetAddress.getLoopbackAddress(), auth.port(), "student1", "pass123").session();
            Session admin = AuthClient.login(InetAddress.getLoopbackAddress(), auth.port(), "admin", "admin123").session();

            // 1. happy path
            String ok = gw(gwPort, "BOOK|LAB_A|S05|" + student.token() + "|" + student.signature());
            check("1. Token from the TLS server books on the gateway -> BOOK_OK with SHA-256 receipt hash",
                    ok.startsWith("BOOK_OK|") && ok.split("\\|")[2].matches("[0-9a-f]{64}"));
            check("2. Seat owner is the user inside the verified token",
                    "student1".equals(seats.getBookedUserId("LAB_A", 5)));

            // 3. tamper with one character of the token
            String tampered = TokenSecurityTest.flipChar(student.token(), 10);
            check("3. One changed token character -> BOOK_FAIL|INVALID_SIGNATURE, seat stays free",
                    gw(gwPort, "BOOK|LAB_A|S06|" + tampered + "|" + student.signature())
                            .equals("BOOK_FAIL|INVALID_SIGNATURE")
                            && !seats.isBooked("LAB_A", 6));

            // 4. the old fake format no longer works
            check("4. Fake/missing credentials are rejected (TOK|SIG, empty)",
                    gw(gwPort, "BOOK|LAB_A|S07|TOK|SIG").equals("BOOK_FAIL|INVALID_SIGNATURE")
                            && gw(gwPort, "BOOK|LAB_A|S07||").equals("BOOK_FAIL|MISSING_TOKEN")
                            && gw(gwPort, "BOOK|LAB_A|S07|student1").equals("BOOK_FAIL|BAD_FRAME")
                            && !seats.isBooked("LAB_A", 7));

            // 5. expired token (genuinely signed with the shared secret, issued 2 hours ago)
            Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
            TokenService.IssuedToken old = new TokenService(SharedSecret.of(SECRET), Duration.ofMinutes(30), past)
                    .issue("student1", Role.STUDENT);
            check("5. Expired token -> BOOK_FAIL|TOKEN_EXPIRED",
                    gw(gwPort, "BOOK|LAB_A|S08|" + old.token() + "|" + old.signature())
                            .equals("BOOK_FAIL|TOKEN_EXPIRED"));

            // 6. RBAC
            check("6. STUDENT sends ADD_LAB -> ADD_LAB_FAIL|FORBIDDEN, lab not created",
                    gw(gwPort, "ADD_LAB|LAB_C|" + student.token() + "|" + student.signature())
                            .equals("ADD_LAB_FAIL|FORBIDDEN")
                            && gw(gwPort, "LIST|LAB_C").equals("ERR|UNKNOWN_LAB"));
            check("7. ADMIN sends ADD_LAB -> ADD_LAB_OK, new lab is listable and bookable",
                    gw(gwPort, "ADD_LAB|LAB_C|" + admin.token() + "|" + admin.signature()).equals("ADD_LAB_OK|LAB_C|20")
                            && gw(gwPort, "LIST|LAB_C").startsWith("SLOTS|LAB_C|S01:FREE")
                            && gw(gwPort, "BOOK|LAB_C|S01|" + student.token() + "|" + student.signature())
                                    .startsWith("BOOK_OK|"));
            check("8. ADD_LAB edge cases: duplicate, bad id, bad frame",
                    gw(gwPort, "ADD_LAB|LAB_C|" + admin.token() + "|" + admin.signature()).equals("ADD_LAB_FAIL|LAB_EXISTS")
                            && gw(gwPort, "ADD_LAB|lab c|" + admin.token() + "|" + admin.signature())
                                    .equals("ADD_LAB_FAIL|INVALID_LAB_ID")
                            && gw(gwPort, "ADD_LAB|LAB_D").equals("ADD_LAB_FAIL|BAD_FRAME"));

            // 9. LIST stays public (no token in the spec's LIST frame)
            check("9. LIST needs no token (unchanged protocol)", gw(gwPort, "LIST|LAB_A").startsWith("SLOTS|LAB_A|"));

            // 10. a gateway with a different secret rejects genuine tokens
            int otherPort = freePort();
            BookingWorkerPool otherPool = new BookingWorkerPool(1);
            NioGatewayServer other = new NioGatewayServer(otherPort, new SeatStore(Map.of("LAB_A", 20)), otherPool,
                    new RoleGuard(new TokenService(SharedSecret.of("a-different-secret-xyz".getBytes(StandardCharsets.UTF_8)))));
            other.start();
            try {
                check("10. Gateway with a different secret -> BOOK_FAIL|INVALID_SIGNATURE",
                        gw(otherPort, "BOOK|LAB_A|S01|" + student.token() + "|" + student.signature())
                                .equals("BOOK_FAIL|INVALID_SIGNATURE"));
            } finally {
                other.stop();
                other.awaitTermination();
            }
        } finally {
            gateway.stop();
            gateway.awaitTermination();
            auth.stop();
            auth.awaitTermination();
            Files.deleteIfExists(serverKs);
            Files.deleteIfExists(trustKs);
            Files.deleteIfExists(dir.resolve("server.cer"));
            Files.deleteIfExists(dir);
        }

        System.out.println();
        System.out.printf("RESULT: %d PASS, %d FAIL%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static String gw(int port, String frame) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            out.write((frame + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
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
