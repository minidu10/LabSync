package check;

import com.labsync.client.NetworkResolver;

/** Stage 1 console check for NetworkResolver. Not part of the submission. */
public class ResolverCheck {
    public static void main(String[] args) {
        String[] hosts = args.length > 0 ? args
                : new String[] { "localhost", "127.0.0.1", "no-such-host.invalid" };
        for (String h : hosts) {
            try {
                System.out.println("OK    " + NetworkResolver.describe(h));
            } catch (java.net.UnknownHostException e) {
                System.out.println("FAIL  Unknown host: " + h);
            }
        }

        String[] uris = {
            "labsync://localhost:5000/receipt/R-1001",
            "http://localhost:5000/receipt/R-1001",
            "labsync://localhost:5000/other/R-1"
        };
        for (String u : uris) {
            try {
                System.out.println("OK    " + NetworkResolver.parseReceiptUri(u));
            } catch (java.net.URISyntaxException e) {
                System.out.println("FAIL  " + e.getMessage());
            }
        }
    }
}
