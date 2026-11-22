package com.labsync.client;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Addressing and naming helpers (Member 5).
 *
 * 1. Turns a hostname typed by the user ("localhost", "lab-server.local")
 *    into an IP address using DNS / the hosts file (InetAddress.getByName).
 * 2. Parses receipt references of the form
 *    unibook://<host>:<port>/receipt/<receiptId> using java.net.URI.
 *
 * No Swing code here - this class can be tested from the console.
 */
public final class NetworkResolver {

    public static final String RECEIPT_SCHEME = "unibook";

    private NetworkResolver() { }

    /**
     * Resolves a hostname or IP literal to an address.
     *
     * @throws UnknownHostException if the name cannot be resolved
     *         (caller shows a friendly message instead of a stack trace)
     */
    public static InetAddress resolve(String host) throws UnknownHostException {
        if (host == null || host.isBlank()) {
            throw new UnknownHostException("Host name is empty");
        }
        return InetAddress.getByName(host.trim());
    }

    /** One-line description for the log, e.g. "localhost -> 127.0.0.1 (all: 127.0.0.1, ::1)". */
    public static String describe(String host) throws UnknownHostException {
        InetAddress primary = resolve(host);
        String all = Arrays.stream(InetAddress.getAllByName(host.trim()))
                .map(InetAddress::getHostAddress)
                .collect(Collectors.joining(", "));
        return host.trim() + " -> " + primary.getHostAddress() + "  (all: " + all + ")";
    }

    /** The parts of a receipt URI, shown in the receipt dialog. */
    public record ReceiptUri(String scheme, String host, int port, String receiptId) { }

    /**
     * Parses and validates a receipt URI such as
     * unibook://localhost:5000/receipt/R-1001.
     *
     * @throws URISyntaxException if the text is not a valid unibook receipt URI
     */
    public static ReceiptUri parseReceiptUri(String text) throws URISyntaxException {
        URI uri = new URI(text.trim());

        if (!RECEIPT_SCHEME.equalsIgnoreCase(uri.getScheme())) {
            throw new URISyntaxException(text, "Scheme must be '" + RECEIPT_SCHEME + "'");
        }
        if (uri.getHost() == null) {
            throw new URISyntaxException(text, "Missing host");
        }
        String path = uri.getPath();                 // "/receipt/R-1001"
        String prefix = "/receipt/";
        if (path == null || !path.startsWith(prefix) || path.length() == prefix.length()) {
            throw new URISyntaxException(text, "Path must be /receipt/<receiptId>");
        }
        return new ReceiptUri(uri.getScheme(), uri.getHost(), uri.getPort(),
                path.substring(prefix.length()));
    }
}
