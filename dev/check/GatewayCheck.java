package check;

import com.labsync.client.GatewayConnection;
import com.labsync.client.NetworkResolver;
import com.labsync.client.Protocol;
import com.labsync.client.Session;

/**
 * Stage 2 console check: LIST and BOOK against a running gateway (mock or real).
 * Usage: java check.GatewayCheck [host] [port] [lab] [seat]
 */
public class GatewayCheck {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        String lab = args.length > 2 ? args[2] : "LAB_A";
        String seat = args.length > 3 ? args[3] : "S05";
        Session fake = new Session("student1", "STUDENT", "TOKEN123", "SIG123");

        try (GatewayConnection conn = new GatewayConnection()) {
            conn.connect(NetworkResolver.resolve(host), port);
            System.out.println("Connected to " + host + ":" + port);

            System.out.println("LIST        -> " + conn.listSeats(lab));
            Protocol.BookResult r1 = conn.book(lab, seat, fake);
            System.out.println("BOOK " + seat + "    -> " + r1);
            Protocol.BookResult r2 = conn.book(lab, seat, fake);
            System.out.println("BOOK again  -> " + r2);
            if (r1.ok()) {
                System.out.println("Receipt URI -> " + NetworkResolver.parseReceiptUri(r1.uri()));
            }
            System.out.println("LIST        -> " + conn.listSeats(lab));
        } catch (java.io.IOException e) {
            System.out.println("Network error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
