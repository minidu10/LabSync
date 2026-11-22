package mock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * DEV-ONLY stand-in for Member 4's TlsAuthServer on port 8443.
 * Needs a keystore: run dev/gen-certs.sh first, then
 *   java -Djavax.net.ssl.keyStore=dev/certs/server.jks \
 *        -Djavax.net.ssl.keyStorePassword=changeit -cp bin/dev mock.MockAuthServer
 */
public class MockAuthServer {

    // userId -> {password, role}
    private static final Map<String, String[]> USERS = Map.of(
            "student1", new String[] { "pass123", "STUDENT" },
            "student2", new String[] { "pass123", "STUDENT" },
            "admin",    new String[] { "admin123", "ADMIN" });

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8443;
        SSLServerSocketFactory f = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
        try (SSLServerSocket server = (SSLServerSocket) f.createServerSocket(port)) {
            System.out.println("[MockAuth] TLS auth listening on port " + port);
            while (true) {
                Socket client = server.accept();
                new Thread(() -> handle(client)).start();
            }
        }
    }

    private static void handle(Socket client) {
        try (client;
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String line = in.readLine();
            if (line == null) {
                return;
            }
            String[] p = line.split("\\|", -1);
            String reply;
            if (p.length == 3 && p[0].equals("LOGIN") && USERS.containsKey(p[1])
                    && USERS.get(p[1])[0].equals(p[2])) {
                String token = UUID.randomUUID().toString().replace("-", "");
                String sig = MockGateway.sha256(token + "|mock-secret");
                reply = "AUTH_OK|" + USERS.get(p[1])[1] + "|" + token + "|" + sig;
            } else {
                reply = "AUTH_FAIL|INVALID_CREDENTIALS";
            }
            System.out.println("[MockAuth] LOGIN " + (p.length > 1 ? p[1] : "?") + " -> " + reply.split("\\|")[0]);
            out.print(reply + "\n");
            out.flush();
        } catch (IOException e) {
            System.out.println("[MockAuth] ! " + e.getMessage());
        }
    }
}
