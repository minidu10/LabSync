package mock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DEV-ONLY stand-in for Member 1's NIO gateway on port 5000.
 * Speaks the same frames as the spec so the client can be built and tested
 * before the real server exists. Deliberately simple: thread per client.
 *
 * Usage: java mock.MockGateway [port] [replyDelayMs]
 *   replyDelayMs > 5000 lets you test the client's read timeout.
 */
public class MockGateway {

    private static final Map<String, Map<String, String>> LABS = new LinkedHashMap<>();
    private static final AtomicInteger receiptCounter = new AtomicInteger(1000);
    private static int port;
    private static long delayMs;

    public static void main(String[] args) throws IOException {
        port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        delayMs = args.length > 1 ? Long.parseLong(args[1]) : 0;
        createLab("LAB_A", 20);
        createLab("LAB_B", 12);
        LABS.get("LAB_A").put("S03", "BOOKED");
        LABS.get("LAB_A").put("S07", "RESERVED");

        try (ServerSocket server = new ServerSocket(port)) {
            log("Mock gateway listening on port " + port + (delayMs > 0 ? " (delay " + delayMs + " ms)" : ""));
            while (true) {
                Socket client = server.accept();
                new Thread(() -> handle(client)).start();
            }
        }
    }

    private static void createLab(String lab, int seats) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 1; i <= seats; i++) {
            m.put(String.format("S%02d", i), "FREE");
        }
        LABS.put(lab, m);
    }

    private static void handle(Socket client) {
        String who = client.getRemoteSocketAddress().toString();
        log("+ connected " + who);
        try (client;
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                log(who + " >> " + line);
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                String reply = process(line.trim());
                log(who + " << " + reply);
                out.print(reply + "\n");
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            log("! " + who + " " + e.getMessage());
        }
        log("- disconnected " + who);
    }

    private static synchronized String process(String line) {
        String[] p = line.split("\\|", -1);
        switch (p[0]) {
            case "LIST": {
                if (p.length != 2 || !LABS.containsKey(p[1])) {
                    return "ERROR|UNKNOWN_LAB";
                }
                StringBuilder sb = new StringBuilder();
                LABS.get(p[1]).forEach((seat, st) -> sb.append(sb.length() == 0 ? "" : ",").append(seat).append(':').append(st));
                return "SLOTS|" + p[1] + "|" + sb;
            }
            case "BOOK": {
                if (p.length != 5) {
                    return "BOOK_FAIL|BAD_REQUEST";
                }
                if (p[3].isEmpty() || p[4].isEmpty()) {
                    return "BOOK_FAIL|INVALID_TOKEN";
                }
                Map<String, String> lab = LABS.get(p[1]);
                if (lab == null || !lab.containsKey(p[2])) {
                    return "BOOK_FAIL|UNKNOWN_SEAT";
                }
                if (!"FREE".equals(lab.get(p[2]))) {
                    return "BOOK_FAIL|SEAT_ALREADY_TAKEN";
                }
                lab.put(p[2], "BOOKED");
                String id = "R" + receiptCounter.incrementAndGet();
                String hash = sha256(id + "|" + p[1] + "|" + p[2] + "|" + p[3]);
                return "BOOK_OK|" + id + "|" + hash + "|unibook://localhost:" + port + "/receipt/" + id;
            }
            default:
                return "ERROR|UNKNOWN_COMMAND";
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void log(String msg) {
        System.out.println("[MockGateway] " + msg);
    }
}
