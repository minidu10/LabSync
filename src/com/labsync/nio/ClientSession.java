package com.labsync.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-connection state for one client attached to the {@link NioGatewayServer} reactor.
 *
 * <p>Each session owns:
 * <ul>
 *   <li>a direct {@link ByteBuffer} that accumulates inbound bytes across selector cycles, so a
 *       frame split over several TCP segments (e.g. {@code "BOO"} then {@code "K|LAB_A|..."}) is
 *       reassembled instead of being lost. Unparsed bytes are kept with {@link ByteBuffer#compact()},
 *       never thrown away with {@code clear()};</li>
 *   <li>a thread-safe outbound queue, so worker-pool threads can call {@link #send(String)} while
 *       only the reactor thread ever touches the socket.</li>
 * </ul>
 *
 * <p>Everything except {@link #send(String)} and the simple getters runs on the reactor thread.
 */
public final class ClientSession {

    /** Upper bound for a single frame; a client that never sends '\n' cannot grow memory forever. */
    static final int READ_BUFFER_SIZE = 8 * 1024;

    private final int id;
    private final SocketChannel channel;
    private final NioGatewayServer server;
    private final String remoteAddress;

    /** Stays in "write mode" between reads; flipped only while frames are being parsed. */
    private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE);
    private final Queue<ByteBuffer> outbound = new ConcurrentLinkedQueue<>();

    /** Re-bound when the selector is rebuilt after an epoll spin. Reactor thread only. */
    private SelectionKey key;
    /** Number of reads that have contributed to the frame currently sitting in readBuffer. */
    private int fragmentReads;
    private boolean closeAfterFlush;
    private volatile boolean closed;

    ClientSession(int id, SocketChannel channel, NioGatewayServer server) {
        this.id = id;
        this.channel = channel;
        this.server = server;
        String addr;
        try {
            addr = String.valueOf(channel.getRemoteAddress());
        } catch (IOException e) {
            addr = "unknown";
        }
        this.remoteAddress = addr;
    }

    public int id() {
        return id;
    }

    public String remoteAddress() {
        return remoteAddress;
    }

    public boolean isOpen() {
        return !closed;
    }

    /**
     * Queues one response frame for this client. Safe to call from any thread (reactor or worker
     * pool). A trailing '\n' is appended if missing, so every frame on the wire is newline-delimited.
     */
    public void send(String frame) {
        if (closed) {
            return;
        }
        String line = frame.endsWith("\n") ? frame : frame + "\n";
        outbound.add(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
        server.requestFlush(this);
    }

    // ------------------------------------------------------------------ reactor-thread internals

    void bindKey(SelectionKey key) {
        this.key = key;
    }

    /** Called by the reactor when the key is readable. Extracts every complete '\n' frame. */
    void onReadable() throws IOException {
        int read = channel.read(readBuffer);
        if (read == -1) {
            close("peer closed the connection");
            return;
        }
        if (read == 0) {
            return;
        }
        NioGatewayServer.log("session#%d read %d byte(s) from %s", id, read, remoteAddress);

        readBuffer.flip(); // write mode -> read mode
        int newline;
        while ((newline = indexOf(readBuffer, (byte) '\n')) >= 0) {
            byte[] bytes = new byte[newline - readBuffer.position()];
            readBuffer.get(bytes);
            readBuffer.get(); // consume the '\n' delimiter

            String frame = new String(bytes, StandardCharsets.UTF_8);
            if (frame.endsWith("\r")) {
                frame = frame.substring(0, frame.length() - 1); // tolerate CRLF clients (telnet)
            }
            if (fragmentReads > 0) {
                NioGatewayServer.log("session#%d [REASSEMBLED] frame rebuilt from %d separate reads: \"%s\"",
                        id, fragmentReads + 1, frame);
                fragmentReads = 0;
            }
            if (!frame.isBlank()) {
                server.dispatch(this, frame);
            }
            if (closed) {
                return;
            }
        }

        if (readBuffer.hasRemaining()) {
            fragmentReads++;
            NioGatewayServer.log("session#%d [PARTIAL] %d byte(s) without '\\n' kept by compact(): \"%s\"",
                    id, readBuffer.remaining(), preview(readBuffer));
        }
        readBuffer.compact(); // keep the unparsed tail, back to write mode for the next read

        if (!readBuffer.hasRemaining()) {
            NioGatewayServer.log("session#%d frame exceeds %d bytes without '\\n', dropping client",
                    id, READ_BUFFER_SIZE);
            readBuffer.clear();
            closeAfterFlush = true;
            send("ERR|FRAME_TOO_LONG");
        }
    }

    /**
     * Writes as much queued output as the socket accepts. OP_WRITE is switched on only when the
     * kernel send buffer is full and switched off again as soon as the queue drains, so the selector
     * never spins on an always-writable channel.
     */
    void flush() {
        if (closed) {
            return;
        }
        try {
            ByteBuffer head;
            while ((head = outbound.peek()) != null) {
                channel.write(head);
                if (head.hasRemaining()) {
                    setWriteInterest(true, head.remaining());
                    return;
                }
                outbound.poll();
            }
            setWriteInterest(false, 0);
            if (closeAfterFlush) {
                close("closed after final response");
            }
        } catch (IOException e) {
            close("write failed: " + e.getMessage());
        }
    }

    void close(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        if (key != null) {
            key.cancel();
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // already closing
        }
        outbound.clear();
        server.onSessionClosed(this, reason);
    }

    private void setWriteInterest(boolean enable, int pendingBytes) {
        SelectionKey k = key;
        if (k == null || !k.isValid()) {
            return;
        }
        int ops = k.interestOps();
        int next = enable ? ops | SelectionKey.OP_WRITE : ops & ~SelectionKey.OP_WRITE;
        if (next == ops) {
            return;
        }
        k.interestOps(next);
        if (enable) {
            NioGatewayServer.log("session#%d send buffer full, %d byte(s) pending -> OP_WRITE enabled",
                    id, pendingBytes);
        } else {
            NioGatewayServer.log("session#%d output drained -> OP_WRITE cleared (OP_READ only)", id);
        }
    }

    private static int indexOf(ByteBuffer buf, byte target) {
        for (int i = buf.position(); i < buf.limit(); i++) {
            if (buf.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    /** Reads the remaining bytes without moving the buffer's position. */
    private static String preview(ByteBuffer buf) {
        ByteBuffer copy = buf.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8).replace("\r", "\\r");
    }

    @Override
    public String toString() {
        return "session#" + id + "(" + remoteAddress + ")";
    }
}
