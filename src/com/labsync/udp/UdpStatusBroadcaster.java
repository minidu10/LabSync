package com.labsync.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side UDP Status Broadcast Service (Port 6000).
 *
 * <p>Dispatches real-time, lightweight seat state notification frames across the local area network
 * whenever laboratory slot reservations, cancellations, or updates occur.
 *
 * <p><b>Lecture Concepts & Architectural Mechanisms:</b>
 * <ul>
 *   <li><b>Connectionless UDP Transport:</b> Uses {@link DatagramSocket} and {@link DatagramPacket}
 *       for ultra-low-latency, fire-and-forget dissemination without TCP handshake overhead.</li>
 *   <li><b>Monotonic Sequence Counter:</b> Each datagram header is stamped with an atomically
 *       incremented sequence number ({@code SEQ:<n>}) via {@link AtomicLong}. This powers the
 *       client-side out-of-order packet loss and stale state rejection filter.</li>
 *   <li><b>Subnet Broadcasting:</b> Explicitly enables {@code SO_BROADCAST} via
 *       {@link DatagramSocket#setBroadcast(boolean)}. Automatically enumerates active network
 *       interfaces to target local subnet broadcast addresses (e.g. {@code 192.168.1.255}), global
 *       limited broadcast ({@code 255.255.255.255}), and loopback ({@code 127.0.0.1}) for single-host
 *       evaluations.</li>
 *   <li><b>Out-of-Order Simulation Hook:</b> Provides {@link #broadcastCustom(long, String, String, String, String)}
 *       to simulate out-of-order packet arrival for practical demo evaluation.</li>
 * </ul>
 *
 * <p>Run standalone: {@code java -cp bin com.labsync.udp.UdpStatusBroadcaster [port]}
 */
public final class UdpStatusBroadcaster implements AutoCloseable {

    public static final int DEFAULT_PORT = 6000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final int destinationPort;
    private final DatagramSocket socket;
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final Set<InetAddress> broadcastTargets;
    private volatile boolean closed;

    /**
     * Initializes the broadcaster targeting the default UDP port 6000.
     *
     * @throws SocketException if the socket cannot be opened or broadcast option cannot be set
     */
    public UdpStatusBroadcaster() throws SocketException {
        this(DEFAULT_PORT);
    }

    /**
     * Initializes the broadcaster targeting the specified UDP destination port.
     *
     * @param destinationPort Target UDP port (typically 6000)
     * @throws SocketException if the socket cannot be opened or broadcast option cannot be set
     */
    public UdpStatusBroadcaster(int destinationPort) throws SocketException {
        this.destinationPort = destinationPort;
        this.socket = new DatagramSocket();
        this.socket.setBroadcast(true);
        this.broadcastTargets = Collections.unmodifiableSet(discoverBroadcastAddresses());

        log("UdpStatusBroadcaster initialized on ephemeral port %d -> target port %d | broadcast targets: %s",
                socket.getLocalPort(), destinationPort, broadcastTargets);
    }

    /**
     * Broadcasts a seat status change stamped with the next monotonic sequence number.
     *
     * <p>Thread-safe and non-blocking for callers; safe to invoke from NIO reactor or worker threads.
     *
     * @param labId  Laboratory identifier (e.g. "LAB_A")
     * @param seatId Seat identifier (e.g. "S05")
     * @param status New seat status (e.g. "BOOKED", "FREE", "RESERVED")
     * @param userId User associated with this state transition (or "SYSTEM")
     * @return The transmitted {@link SeatStatusUpdate}
     * @throws IOException if network transmission encounters an unrecoverable failure
     */
    public SeatStatusUpdate broadcast(String labId, String seatId, String status, String userId) throws IOException {
        long seq = sequenceCounter.incrementAndGet();
        return transmit(seq, labId, seatId, status, userId, false);
    }

    /**
     * Broadcasts a custom status frame with an explicitly specified sequence number.
     *
     * <p><b>Used for the 2-Minute Practical Demonstration:</b> allows injecting an out-of-order
     * frame (e.g. transmitting SEQ:3 after SEQ:7) to verify that clients drop stale packets.
     *
     * @param customSeq Explicit sequence number to stamp onto the frame header
     * @param labId     Laboratory identifier
     * @param seatId    Seat identifier
     * @param status    Seat status
     * @param userId    User identifier
     * @return The transmitted {@link SeatStatusUpdate}
     * @throws IOException if datagram transmission fails
     */
    public SeatStatusUpdate broadcastCustom(long customSeq, String labId, String seatId, String status, String userId)
            throws IOException {
        return transmit(customSeq, labId, seatId, status, userId, true);
    }

    /**
     * Internal transmission routine that serializes the frame and dispatches it to all targets.
     */
    private synchronized SeatStatusUpdate transmit(
            long seq, String labId, String seatId, String status, String userId, boolean isSimulated)
            throws IOException {
        if (closed) {
            throw new IllegalStateException("Broadcaster is closed");
        }

        SeatStatusUpdate update = new SeatStatusUpdate(seq, labId, seatId, status, userId);
        String frame = update.toFrame();
        byte[] payload = frame.getBytes(StandardCharsets.UTF_8);

        int successfulSends = 0;
        for (InetAddress target : broadcastTargets) {
            try {
                DatagramPacket packet = new DatagramPacket(payload, payload.length, target, destinationPort);
                socket.send(packet);
                successfulSends++;
            } catch (IOException e) {
                log("WARN failed to send broadcast to %s:%d - %s", target.getHostAddress(), destinationPort, e.getMessage());
            }
        }

        if (isSimulated) {
            log("[SIMULATED TX] Frame: \"%s\" (len=%d bytes) dispatched to %d target(s) [Simulated Out-of-Order Demo]",
                    frame, payload.length, successfulSends);
        } else {
            log("[TX] Broadcast frame: \"%s\" (len=%d bytes, seq=%d) dispatched to %d target(s)",
                    frame, payload.length, seq, successfulSends);
        }

        return update;
    }

    /**
     * Discovers all valid subnet broadcast destinations, global broadcast, and local loopback.
     *
     * <p>This guarantees packet delivery whether operating on a physical campus LAN, behind Wi-Fi
     * isolation, or during an offline single-laptop evaluation demonstration.
     *
     * @return Set of resolved {@link InetAddress} instances to broadcast to
     */
    private Set<InetAddress> discoverBroadcastAddresses() {
        Set<InetAddress> targets = new LinkedHashSet<>();

        // 1. Always include global limited broadcast address (255.255.255.255)
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }

        // 2. Discover local subnet broadcast addresses from active network interfaces
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface ni : Collections.list(interfaces)) {
                    try {
                        if (!ni.isUp() || ni.isLoopback()) {
                            continue;
                        }
                        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                            InetAddress bcast = ia.getBroadcast();
                            if (bcast != null) {
                                targets.add(bcast);
                            }
                        }
                    } catch (SocketException ignored) {
                        // Interface queried during state transition; skip
                    }
                }
            }
        } catch (SocketException e) {
            log("WARN Could not enumerate network interfaces: %s", e.getMessage());
        }

        // 3. Always include local loopback for single-machine practical evaluations
        try {
            targets.add(InetAddress.getByName("127.0.0.1"));
        } catch (Exception ignored) {
        }

        return targets;
    }

    /** Returns the current monotonic sequence counter value. */
    public long getCurrentSequence() {
        return sequenceCounter.get();
    }

    /** Returns the target destination UDP port. */
    public int getDestinationPort() {
        return destinationPort;
    }

    /** Returns true if the underlying UDP socket is closed. */
    public boolean isClosed() {
        return closed || socket.isClosed();
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            socket.close();
            log("Broadcaster closed successfully");
        }
    }

    static void log(String format, Object... args) {
        System.out.printf("%s [LabSync-UdpBroadcaster] %s%n",
                LocalTime.now().format(TIME), String.format(format, args));
    }

    /**
     * Standalone broadcaster demo / test utility.
     */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        System.out.println("Starting LabSync UDP Status Broadcaster on target port " + port);

        try (UdpStatusBroadcaster broadcaster = new UdpStatusBroadcaster(port)) {
            System.out.println("Broadcasting 5 sequential seat updates (1-second intervals)...");
            for (int i = 1; i <= 5; i++) {
                String seat = String.format("S%02d", i);
                broadcaster.broadcast("LAB_A", seat, "BOOKED", "student" + (100 + i));
                Thread.sleep(1000);
            }

            System.out.println("\nNow demonstrating Out-of-Order Injection for evaluation defense:");
            System.out.println("Broadcasting SEQ:10 first...");
            broadcaster.broadcastCustom(10, "LAB_A", "S10", "BOOKED", "userX");
            Thread.sleep(500);

            System.out.println("Broadcasting out-of-order SEQ:3 (should be dropped by client)...");
            broadcaster.broadcastCustom(3, "LAB_A", "S10", "FREE", "SYSTEM");

            System.out.println("\nDemo transmission complete.");
        }
    }
}

