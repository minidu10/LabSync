package com.labsync.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * TCP connection to the NIO Booking Gateway (port 5000).
 *
 * Design decisions:
 *  - The CLIENT uses a normal blocking Socket. Only the server needs NIO;
 *    a client talks to one server, so blocking I/O on a background thread
 *    is simpler and correct.
 *  - request() is synchronized: one request -> one reply line. Two threads
 *    can never interleave frames, so a reply always matches its request.
 *  - Timeouts: connect() gives up after 3 s, a reply must arrive within 5 s
 *    (SocketTimeoutException) - the UI never waits forever.
 *  - On ANY I/O error the socket is closed. After a timeout we cannot know
 *    whether a late reply is still coming, so reusing the socket would risk
 *    matching the wrong reply to the next request.
 *
 * Methods here BLOCK - never call them on the Swing Event Dispatch Thread.
 */
public class GatewayConnection implements Closeable {

    public static final int DEFAULT_PORT = 5000;
    public static final int CONNECT_TIMEOUT_MS = 3000;
    public static final int READ_TIMEOUT_MS = 5000;

    // volatile: close() may be called from another thread (e.g. the UI)
    private volatile Socket socket;
    private BufferedReader in;
    private BufferedWriter out;

    public synchronized void connect(InetAddress address, int port) throws IOException {
        close();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            socket = s;
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    public boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    /** Sends one frame and waits for one reply line. */
    public synchronized String request(String frame) throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to gateway");
        }
        try {
            out.write(frame);
            out.write('\n');          // frame delimiter required by the protocol
            out.flush();
            String reply = in.readLine();
            if (reply == null) {      // server closed the connection
                throw new EOFException("Server closed the connection");
            }
            return reply;
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /** LIST|lab -> map of seatId -> status. */
    public Map<String, String> listSeats(String labId) throws IOException {
        return Protocol.parseSlots(request(Protocol.list(labId)), labId);
    }

    /** BOOK|lab|seat|token|sig -> receipt or failure reason. */
    public Protocol.BookResult book(String labId, String seatId, Session session) throws IOException {
        return Protocol.parseBook(request(Protocol.book(labId, seatId, session)));
    }

    /** CANCEL|lab|seat|token|sig -> ok or failure reason. */
    public Protocol.CancelResult cancel(String labId, String seatId, Session session) throws IOException {
        return Protocol.parseCancel(request(Protocol.cancel(labId, seatId, session)));
    }

    /** Not synchronized on purpose: it must be able to interrupt a blocked read. */
    @Override
    public void close() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing useful to do - we are discarding the socket anyway
            }
        }
    }
}
