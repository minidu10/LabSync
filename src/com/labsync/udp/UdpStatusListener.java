package com.labsync.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side UDP Live Status Listener (Port 6000).
 *
 * <p>Listens continuously in a background daemon thread for incoming UDP broadcast status frames
 * and updates client UI components asynchronously.
 *
 * <p><b>Lecture Concepts & Architectural Safeguards:</b>
 * <ul>
 *   <li><b>Connectionless UDP Transport:</b> Consumes lightweight broadcast datagrams using
 *       {@link DatagramSocket} and {@link DatagramPacket} without holding persistent TCP connections.</li>
 *   <li><b>Out-of-Order "Zombie State" Protection:</b> Enforces a monotonic sequence number check.
 *       Maintains {@code lastSeenSeq}. If {@code incomingSeq <= lastSeenSeq}, the datagram is
 *       identified as a delayed, duplicate, or out-of-order packet and is immediately <b>discarded</b>,
 *       preventing the client UI seat map from rendering stale states.</li>
 *   <li><b>Subnet Broadcast & Socket Reuse (SO_REUSEADDR):</b> Uses an unbound socket with
 *       {@link DatagramSocket#setReuseAddress(boolean)} set to {@code true} before binding. This
 *       allows multiple client applications running on the same host (such as the side-by-side
 *       evaluation demonstration) to listen on Port 6000 simultaneously without {@link java.net.BindException}.</li>
 *   <li><b>Decoupled Asynchronous UI Dispatch:</b> Received updates are dispatched to registered
 *       {@link StatusListener} callbacks via a decoupled worker thread, ensuring the network receive
 *       loop is never blocked by UI rendering operations.</li>
 * </ul>
 *
 * <p>Run standalone: {@code java -cp bin com.labsync.udp.UdpStatusListener [port]}
 */
public final class UdpStatusListener implements Runnable, AutoCloseable {

    public static final int DEFAULT_PORT = 6000;
    private static final int BUFFER_SIZE = 2048;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /**
     * Listener callback interface for receiving asynchronous seat status updates.
     */
    @FunctionalInterface
    public interface StatusListener {
        /**
         * Invoked whenever an in-order, verified status update datagram is accepted.
         *
         * @param update Validated seat status update
         */
        void onStatusUpdate(SeatStatusUpdate update);
    }

    private final int port;
    private final List<StatusListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService dispatchExecutor;

    // --- Sequence Counter & Safeguard Metrics ---
    private final AtomicLong lastSeenSeq = new AtomicLong(0);
    private final AtomicLong totalReceived = new AtomicLong(0);
    private final AtomicLong acceptedPackets = new AtomicLong(0);
    private final AtomicLong droppedPackets = new AtomicLong(0);

    private DatagramSocket socket;
    private Thread listenerThread;
    private volatile boolean running;

    /**
     * Initializes the listener for the default UDP port 6000.
     */
    public UdpStatusListener() {
        this(DEFAULT_PORT);
    }

    /**
     * Initializes the listener for a specific UDP port.
     *
     * @param port The UDP port to bind and listen on
     */
    public UdpStatusListener(int port) {
        this.port = port;
        this.dispatchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LabSync-UdpDispatch");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Registers a status listener callback. Thread-safe.
     *
     * @param listener Callback to receive validated status updates
     */
    public void addStatusListener(StatusListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Unregisters a status listener callback. Thread-safe.
     *
     * @param listener Callback to remove
     */
    public void removeStatusListener(StatusListener listener) {
        listeners.remove(listener);
    }

    /**
     * Binds the UDP socket with {@code SO_REUSEADDR} and starts the background listener thread.
     *
     * @throws SocketException if socket binding fails
     */
    public synchronized void start() throws SocketException {
        if (running) {
            return;
        }

        // Low-level socket creation: unbound socket configured with SO_REUSEADDR before binding
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));

        running = true;
        listenerThread = new Thread(this, "LabSync-UdpListener-" + port);
        listenerThread.setDaemon(true);
        listenerThread.start();

        log("UdpStatusListener bound to port %d (SO_REUSEADDR=true, reusePort supported) | Thread: %s",
                port, listenerThread.getName());
    }

    /**
     * Core UDP receive loop running on the dedicated daemon thread.
     */
    @Override
    public void run() {
        byte[] buffer = new byte[BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        log("UDP receive loop started - waiting for live broadcast datagrams on port %d...", port);

        while (running && !socket.isClosed()) {
            try {
                // Blocking receive call waiting for network packets
                socket.receive(packet);
                totalReceived.incrementAndGet();

                String rawFrame = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                processIncomingFrame(rawFrame);

                // Reset packet buffer length before next receive cycle
                packet.setLength(buffer.length);

            } catch (SocketException e) {
                if (!running) {
                    // Normal closure during stop()
                    break;
                }
                log("SocketException in receive loop: %s", e.getMessage());
            } catch (IOException e) {
                if (running) {
                    log("I/O error receiving UDP datagram: %s", e.getMessage());
                }
            }
        }

        log("UDP receive loop stopped on port %d", port);
    }

    /**
     * Parses frame and enforces the Monotonic Sequence Safeguard ("Zombie State" Protection).
     *
     * <p>Mitigation Logic:
     * <ol>
     *   <li>If {@code incomingSeq <= lastSeenSeq}: Datagram is rejected and dropped. Prevents
     *       delayed wireless packets from overwriting newer seat states.</li>
     *   <li>If {@code incomingSeq > lastSeenSeq}: {@code lastSeenSeq} is updated to {@code incomingSeq},
     *       and the update is dispatched to UI listeners.</li>
     * </ol>
     *
     * @param rawFrame Raw string received from UDP datagram
     */
    void processIncomingFrame(String rawFrame) {
        SeatStatusUpdate update;
        try {
            update = SeatStatusUpdate.parse(rawFrame);
        } catch (IllegalArgumentException e) {
            droppedPackets.incrementAndGet();
            log("WARN [DROP] Malformed frame discarded: \"%s\" (Reason: %s)", rawFrame, e.getMessage());
            return;
        }

        long incomingSeq = update.sequenceNumber();
        long currentLastSeen = lastSeenSeq.get();

        // -------------------------------------------------------------------------
        // CRITICAL SAFEGUARD: Out-of-Order Packet Loss & Zombie State Mitigation
        // -------------------------------------------------------------------------
        if (incomingSeq <= currentLastSeen) {
            droppedPackets.incrementAndGet();
            log("WARN [DROP] Out-of-order / stale packet rejected: incomingSeq=%d <= lastSeenSeq=%d | Frame: \"%s\" "
                    + "(Zombie state mitigated: UI protected from stale rollback)",
                    incomingSeq, currentLastSeen, rawFrame);
            return;
        }

        // Atomically update highest observed sequence number
        lastSeenSeq.set(incomingSeq);
        acceptedPackets.incrementAndGet();

        log("[ACCEPT] Valid in-order update accepted: SEQ:%d (lastSeenSeq now %d) | %s %s -> %s by %s",
                incomingSeq, incomingSeq, update.labId(), update.seatId(), update.status(), update.userId());

        // Asynchronously notify UI listeners to decouple networking from rendering
        dispatchAsync(update);
    }

    private void dispatchAsync(SeatStatusUpdate update) {
        if (listeners.isEmpty()) {
            return;
        }
        dispatchExecutor.submit(() -> {
            for (StatusListener listener : listeners) {
                try {
                    listener.onStatusUpdate(update);
                } catch (RuntimeException e) {
                    log("Error in UI status listener callback: %s", e.getMessage());
                }
            }
        });
    }

    /** Returns the highest monotonic sequence number processed so far. */
    public long getLastSeenSeq() {
        return lastSeenSeq.get();
    }

    /** Returns total datagrams received by this listener. */
    public long getTotalReceived() {
        return totalReceived.get();
    }

    /** Returns the count of accepted in-order packets. */
    public long getAcceptedPackets() {
        return acceptedPackets.get();
    }

    /** Returns the count of rejected out-of-order or duplicate packets. */
    public long getDroppedPackets() {
        return droppedPackets.get();
    }

    /** Returns the listening UDP port. */
    public int getPort() {
        return port;
    }

    /** Returns true if the listener is currently running. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Stops the listener, closes the socket, and shuts down the dispatch executor.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        dispatchExecutor.shutdownNow();
        log("Listener stopped (Summary: total=%d, accepted=%d, dropped=%d, highestSeq=%d)",
                totalReceived.get(), acceptedPackets.get(), droppedPackets.get(), lastSeenSeq.get());
    }

    @Override
    public void close() {
        stop();
    }

    static void log(String format, Object... args) {
        System.out.printf("%s [LabSync-UdpListener] %s%n",
                LocalTime.now().format(TIME), String.format(format, args));
    }

    /**
     * Standalone listener demo runner.
     */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        System.out.println("Starting LabSync UDP Live Status Listener on port " + port);

        UdpStatusListener listener = new UdpStatusListener(port);
        listener.addStatusListener(update -> {
            System.out.printf("  --> [UI NOTIFIED] Lab: %s | Seat: %s | State: %s | User: %s%n",
                    update.labId(), update.seatId(), update.status(), update.userId());
        });

        Runtime.getRuntime().addShutdownHook(new Thread(listener::stop, "UdpListener-Shutdown"));
        listener.start();

        System.out.println("Listening... Press Ctrl+C or Enter to stop.");
        System.in.read();
        listener.stop();
    }
}

