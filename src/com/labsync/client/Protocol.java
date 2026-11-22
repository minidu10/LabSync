package com.labsync.client;

import java.net.ProtocolException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The shared text protocol, client side - kept in ONE place so the frame
 * formats match the team specification exactly.
 *
 *   LOGIN|<userId>|<password>                  -> AUTH_OK|<ROLE>|<TOKEN>|<SIG>
 *                                                 AUTH_FAIL|<reason>
 *   LIST|<labId>                               -> SLOTS|<labId>|S01:FREE,S02:BOOKED,...
 *   BOOK|<labId>|<seatId>|<TOKEN>|<SIG>        -> BOOK_OK|<receiptId>|<hash>|<uri>
 *                                                 BOOK_FAIL|<reason>
 *
 * Every frame is one UTF-8 line ending in '\n' (the '\n' is added by the
 * connection classes, not here).
 */
public final class Protocol {

    public static final String SEP = "|";

    private Protocol() { }

    // ---------- building requests ----------

    public static String login(String userId, String password) {
        return "LOGIN" + SEP + clean(userId) + SEP + clean(password);
    }

    public static String list(String labId) {
        return "LIST" + SEP + clean(labId);
    }

    public static String book(String labId, String seatId, Session s) {
        return "BOOK" + SEP + clean(labId) + SEP + clean(seatId)
                + SEP + s.token() + SEP + s.signature();
    }

    /** Fields must not contain the separator or a newline, or the frame would break. */
    private static String clean(String field) {
        if (field == null || field.isEmpty() || field.contains(SEP)
                || field.contains("\n") || field.contains("\r")) {
            throw new IllegalArgumentException("Invalid field: '" + field + "'");
        }
        return field;
    }

    // ---------- parsing responses ----------

    /** SLOTS|LAB_A|S01:FREE,S02:BOOKED -> {S01=FREE, S02=BOOKED} (keeps server order). */
    public static Map<String, String> parseSlots(String line, String expectedLab)
            throws ProtocolException {
        String[] p = split(line);
        if (p.length != 3 || !p[0].equals("SLOTS")) {
            throw new ProtocolException("Unexpected reply to LIST: " + line);
        }
        if (!p[1].equals(expectedLab)) {
            throw new ProtocolException("Reply was for lab " + p[1] + ", expected " + expectedLab);
        }
        Map<String, String> seats = new LinkedHashMap<>();
        if (p[2].isEmpty()) {
            return seats;
        }
        for (String entry : p[2].split(",")) {
            String[] kv = entry.split(":");
            if (kv.length != 2) {
                throw new ProtocolException("Bad seat entry: " + entry);
            }
            seats.put(kv[0].trim(), kv[1].trim());
        }
        return seats;
    }

    /** Result of a BOOK request: either a receipt or a failure reason. */
    public record BookResult(boolean ok, String receiptId, String hash, String uri, String reason) {
        static BookResult success(String id, String hash, String uri) {
            return new BookResult(true, id, hash, uri, null);
        }
        static BookResult failure(String reason) {
            return new BookResult(false, null, null, null, reason);
        }
    }

    public static BookResult parseBook(String line) throws ProtocolException {
        String[] p = split(line);
        if (p[0].equals("BOOK_OK") && p.length == 4) {
            return BookResult.success(p[1], p[2], p[3]);
        }
        if (p[0].equals("BOOK_FAIL") && p.length >= 2) {
            return BookResult.failure(p[1]);
        }
        // Any other reply (e.g. an authorization error from the server)
        // is shown to the user as a failure instead of crashing.
        if (p.length >= 2 && (p[0].endsWith("FAIL") || p[0].startsWith("ERR"))) {
            return BookResult.failure(p[0] + ": " + p[1]);
        }
        throw new ProtocolException("Unexpected reply to BOOK: " + line);
    }

    /**
     * AUTH_OK|STUDENT|token|sig -> Session.
     * @throws AuthException for AUTH_FAIL (wrong user/password)
     */
    public static Session parseAuth(String userId, String line)
            throws ProtocolException, AuthException {
        String[] p = split(line);
        if (p[0].equals("AUTH_OK") && p.length == 4) {
            return new Session(userId, p[1], p[2], p[3]);
        }
        if (p[0].equals("AUTH_FAIL")) {
            throw new AuthException(p.length > 1 ? p[1] : "AUTH_FAIL");
        }
        throw new ProtocolException("Unexpected reply to LOGIN: " + line);
    }

    private static String[] split(String line) {
        // limit -1 keeps empty trailing fields, so "SLOTS|LAB_A|" has 3 parts
        return line.trim().split("\\|", -1);
    }
}
