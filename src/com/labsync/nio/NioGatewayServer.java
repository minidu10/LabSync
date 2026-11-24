package com.labsync.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Objects;
import com.labsync.engine.BookingReceipt;
import com.labsync.engine.BookingWorkerPool;
import com.labsync.engine.SeatStore;
import com.labsync.security.AuthorizationException;
import com.labsync.security.RoleGuard;
import com.labsync.security.TokenService;

/**
 * LabSync NIO Booking Gateway (TCP port 5000).
 *
 * <p>A single-threaded reactor: one {@link Selector} multiplexes the listening
 * {@link ServerSocketChannel} and every client {@link SocketChannel}, so N clients cost N
 * {@link ClientSession} objects, not N threads.
 *
 * <p>Safeguards implemented here:
 * <ul>
 *   <li><b>Framing / partial reads</b> - see {@link ClientSession#onReadable()}.</li>
 *   <li><b>OP_WRITE spin</b> - channels sit on OP_READ; OP_WRITE is set only while a write is
 *       incomplete (see {@link ClientSession#flush()}).</li>
 *   <li><b>Epoll 0-key spin (JDK-8238279)</b> - consecutive premature zero-key wakeups are counted;
 *       at {@value #EPOLL_SPIN_THRESHOLD} the selector is rebuilt and all channels migrated.</li>
 *   <li><b>Worker hand-off</b> - {@link ClientSession#send(String)} and {@link #execute(Runnable)}
 *       are thread-safe; they queue work for the reactor and call {@code selector.wakeup()}.</li>
 * </ul>
 *
 * <p>Run: {@code java -cp bin com.labsync.nio.NioGatewayServer [port]}
 * (add {@code -Dlabsync.debug=true} to enable the {@code SIMULATE_EPOLL_SPIN} demo command).
 */
public final class NioGatewayServer implements Runnable {

    public static final int DEFAULT_PORT = 5000;
    static final int EPOLL_SPIN_THRESHOLD = 512;

    private static final long SELECT_TIMEOUT_MS = 1000;
    /** A select() that returns 0 keys sooner than this, without a wakeup(), counts as premature. */
    private static final long PREMATURE_WAKEUP_NANOS =
            TimeUnit.MILLISECONDS.toNanos(SELECT_TIMEOUT_MS) - TimeUnit.MILLISECONDS.toNanos(5);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /**
     * Integration point for the engine (Member 2) and security (Member 4) layers. Called on the
     * reactor thread for every complete frame; implementations must not block. Slow work goes to a
     * worker pool, which replies later through {@link ClientSession#send(String)}.
     */
    @FunctionalInterface
    public interface FrameHandler {
        void onFrame(ClientSession session, String frame);
    }

    private final int port;
    private final FrameHandler handler;
    private final BookingWorkerPool bookingPool;
    private final boolean debug = Boolean.getBoolean("labsync.debug");

    private final Queue<Runnable> reactorTasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean wakeupPending = new AtomicBoolean();
    private final AtomicInteger sessionIds = new AtomicInteger();
    private final Set<ClientSession> sessions = new HashSet<>(); // reactor thread only

    private volatile Selector selector;
    private volatile Thread reactorThread;
    private volatile boolean running;
    private volatile boolean simulateEpollSpin;
    private ServerSocketChannel serverChannel;

    private long framesProcessed;
    private int selectorRebuilds;

    public NioGatewayServer(int port, FrameHandler handler) {
        this.port = port;
        this.handler = handler;
        this.bookingPool = null;
    }

    public NioGatewayServer(int port, FrameHandler handler, BookingWorkerPool bookingPool) {
        this.port = port;
        this.handler = handler;
        this.bookingPool = bookingPool;
    }

    /**
     * Engine-backed gateway secured with the shared secret from the environment
     * (see {@link com.labsync.security.SharedSecret}), so it accepts tokens from TlsAuthServer.
     */
    public NioGatewayServer(int port, SeatStore seatStore,
            BookingWorkerPool bookingPool) {
        this(port, seatStore, bookingPool, new RoleGuard(TokenService.fromEnvironment()));
    }

    /** Engine-backed gateway with an explicit {@link RoleGuard} (Member 4) for token checks. */
    public NioGatewayServer(int port, SeatStore seatStore,
            BookingWorkerPool bookingPool, RoleGuard roleGuard) {
        this.port = port;
        this.bookingPool = Objects.requireNonNull(
                bookingPool, "bookingPool must not be null");
        this.handler = new EngineFrameHandler(
                this,
                Objects.requireNonNull(seatStore, "seatStore must not be null"),
                bookingPool,
                Objects.requireNonNull(roleGuard, "roleGuard must not be null"));
    }

    /** Binds port and starts the reactor on a dedicated thread named {@code LabSync-Reactor}. */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running = true;
        reactorThread = new Thread(this, "LabSync-Reactor");
        reactorThread.start();
        log("NIO gateway listening on %s (non-blocking=%s, selector=%s)",
                serverChannel.getLocalAddress(), !serverChannel.isBlocking(),
                selector.getClass().getSimpleName());
    }

    public void stop() {
        running = false;
        Selector sel = selector;
        if (sel != null) {
            sel.wakeup();
        }
    }

    public void awaitTermination() throws InterruptedException {
        Thread t = reactorThread;
        if (t != null) {
            t.join();
        }
    }

    /** Runs a task on the reactor thread. Safe to call from any thread. */
    public void execute(Runnable task) {
        reactorTasks.add(task);
        wakeup();
    }

    // ---------------------------------------------------------------------------- reactor loop

    @Override
    public void run() {
        log("reactor loop started - every client is served by this one thread");
        int zeroKeyWakeups = 0;
        try {
            while (running) {
                Selector sel = selector;
                long start = System.nanoTime();
                int ready = simulateEpollSpin ? sel.selectNow() : sel.select(SELECT_TIMEOUT_MS);
                long elapsed = System.nanoTime() - start;
                boolean wokenOnPurpose = wakeupPending.getAndSet(false);

                runReactorTasks();
                if (!running) {
                    break;
                }

                if (ready == 0) {
                    if (!wokenOnPurpose && elapsed < PREMATURE_WAKEUP_NANOS) {
                        zeroKeyWakeups++;
                        if (zeroKeyWakeups % 128 == 0) {
                            log("WARN %d consecutive premature 0-key selector wakeups", zeroKeyWakeups);
                        }
                        if (zeroKeyWakeups >= EPOLL_SPIN_THRESHOLD) {
                            log("WARN epoll 0-key spin detected (threshold %d, JDK-8238279) - rebuilding selector",
                                    EPOLL_SPIN_THRESHOLD);
                            rebuildSelector();
                            zeroKeyWakeups = 0;
                            simulateEpollSpin = false;
                        }
                    } else {
                        zeroKeyWakeups = 0; // normal timeout or deliberate wakeup()
                    }
                    continue;
                }

                zeroKeyWakeups = 0;
                processSelectedKeys(sel);
            }
        } catch (IOException e) {
            log("FATAL reactor failure: %s", e);
        } finally {
            shutdown();
        }
    }

    private void processSelectedKeys(Selector sel) {
        Iterator<SelectionKey> it = sel.selectedKeys().iterator();
        while (it.hasNext()) {
            SelectionKey key = it.next();
            it.remove();
            if (!key.isValid()) {
                continue;
            }
            ClientSession session = key.attachment() instanceof ClientSession s ? s : null;
            try {
                if (key.isAcceptable()) {
                    accept();
                    continue;
                }
                if (session == null) {
                    continue;
                }
                if (key.isReadable()) {
                    session.onReadable();
                }
                if (key.isValid() && key.isWritable()) {
                    session.flush();
                }
            } catch (CancelledKeyException e) {
                if (session != null) {
                    session.close("key cancelled");
                }
            } catch (IOException e) {
                if (session != null) {
                    session.close("I/O error: " + e.getMessage());
                } else {
                    log("accept failed: %s", e.getMessage());
                }
            }
        }
    }

    private void accept() throws IOException {
        SocketChannel channel = serverChannel.accept();
        if (channel == null) {
            return;
        }
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.TCP_NODELAY, true);

        ClientSession session = new ClientSession(sessionIds.incrementAndGet(), channel, this);
        session.bindKey(channel.register(selector, SelectionKey.OP_READ, session));
        sessions.add(session);
        log("accepted %s | active sessions=%d | JVM threads=%d", session, sessions.size(), Thread.activeCount());
    }

    /** Called by a session for each complete frame. Reactor thread only. */
    void dispatch(ClientSession session, String frame) {
        framesProcessed++;
        log("session#%d frame \"%s\" (active sessions=%d, JVM threads=%d)",
                session.id(), frame, sessions.size(), Thread.activeCount());

        if (frame.equals("STATS")) {
            session.send(String.format("STATS|sessions=%d|reactorThread=%s|jvmThreads=%d|frames=%d|selectorRebuilds=%d",
                    sessions.size(), Thread.currentThread().getName(), Thread.activeCount(),
                    framesProcessed, selectorRebuilds));
            return;
        }
        if (debug && frame.equals("SIMULATE_EPOLL_SPIN")) {
            simulateEpollSpin = true;
            log("DEBUG simulating epoll bug: selector now returns 0 keys immediately");
            session.send("OK|EPOLL_SPIN_SIMULATION_STARTED");
            return;
        }
        try {
            handler.onFrame(session, frame);
        } catch (RuntimeException e) {
            log("handler error on session#%d: %s", session.id(), e);
            session.send("ERR|INTERNAL");
        }
    }

    /** Called by {@link ClientSession#send(String)} from any thread. */
    void requestFlush(ClientSession session) {
        if (Thread.currentThread() == reactorThread) {
            session.flush();
        } else {
            execute(session::flush);
        }
    }

    void onSessionClosed(ClientSession session, String reason) {
        sessions.remove(session);
        log("closed %s (%s) | active sessions=%d", session, reason, sessions.size());
    }

    private void wakeup() {
        // Only the first caller pays for wakeup(); the flag is reset each loop before tasks run.
        if (wakeupPending.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }

    private void runReactorTasks() {
        Runnable task;
        while ((task = reactorTasks.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                log("reactor task failed: %s", e);
            }
        }
    }

    /** Opens a fresh selector, moves every live channel (with its interest ops) onto it, closes the old one. */
    private void rebuildSelector() throws IOException {
        Selector old = selector;
        Selector fresh = Selector.open();
        int migrated = 0;
        for (SelectionKey key : old.keys()) {
            if (!key.isValid()) {
                continue;
            }
            SelectableChannel channel = key.channel();
            int ops = key.interestOps();
            Object attachment = key.attachment();
            key.cancel();
            SelectionKey newKey = channel.register(fresh, ops, attachment);
            if (attachment instanceof ClientSession s) {
                s.bindKey(newKey);
            }
            migrated++;
        }
        selector = fresh;
        old.close();
        selectorRebuilds++;
        log("selector rebuilt: %d channel(s) migrated, old selector closed (rebuild #%d)", migrated, selectorRebuilds);
    }

    private void shutdown() {
        for (ClientSession s : new HashSet<>(sessions)) {
            s.close("server shutting down");
        }
        if (bookingPool != null) {
            bookingPool.shutdown();
        }
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
            selector.close();
        } catch (IOException ignored) {
            // shutting down
        }
        log("reactor stopped");
    }

    static void log(String format, Object... args) {
        System.out.printf("%s [%s] %s%n", LocalTime.now().format(TIME),
                Thread.currentThread().getName(), String.format(format, args));
    }

    // ------------------------------------------------------------------------- engine integration

    /**
     * Bridges the existing TCP protocol to Member 2's SeatStore and worker pool.
     * Worker callbacks never send through sockets directly; they enqueue reactor
     * tasks, which call {@link #wakeup()} before the reactor sends the response.
     *
     * <p>Security (Member 4): BOOK and ADD_LAB carry {@code <TOKEN>|<SIG>} issued by TlsAuthServer.
     * {@link RoleGuard#authorize} verifies the HMAC-SHA256 signature, decrypts the AES token,
     * checks expiry and role. The acting user comes from the verified token, never from the frame.
     * This costs one HMAC plus one AES-GCM decrypt (microseconds), so it stays on the reactor thread.
     */
    static final class EngineFrameHandler implements FrameHandler {
        /** Seats per lab; LIST and seat parsing already assume this fixed layout. */
        static final int SEATS_PER_LAB = 20;

        private final NioGatewayServer gateway;
        private final SeatStore seatStore;
        private final BookingWorkerPool workerPool;
        private final RoleGuard roleGuard;

        EngineFrameHandler(NioGatewayServer gateway, SeatStore seatStore,
                BookingWorkerPool workerPool, RoleGuard roleGuard) {
            this.gateway = gateway;
            this.seatStore = seatStore;
            this.workerPool = workerPool;
            this.roleGuard = roleGuard;
        }

        @Override
        public void onFrame(ClientSession session, String frame) {
            String[] parts = frame.split("\\|", -1);
            switch (parts[0]) {
                case "LIST" -> handleList(session, parts);
                case "BOOK" -> handleBook(session, parts);
                case "ADD_LAB" -> handleAddLab(session, parts);
                default -> session.send("ERR|UNKNOWN_COMMAND");
            }
        }

        /**
         * Runs the RoleGuard check; on failure logs it, sends {@code <failPrefix>|<REASON>} and
         * returns {@code null}.
         */
        private TokenService.Claims authorize(ClientSession session, RoleGuard.Action action,
                String token, String signature, String failPrefix) {
            try {
                TokenService.Claims claims = roleGuard.authorize(action, token, signature);
                NioGatewayServer.log("session#%d SECURITY %s authorized: user=%s role=%s (signature OK, token valid until %s)",
                        session.id(), action, claims.userId(), claims.role(), claims.expiresAt());
                return claims;
            } catch (AuthorizationException e) {
                NioGatewayServer.log("session#%d SECURITY %s REJECTED -> %s: %s",
                        session.id(), action, e.reason(), e.getMessage());
                session.send(failPrefix + "|" + e.reason());
                return null;
            }
        }

        /** ADD_LAB|&lt;labId&gt;|&lt;TOKEN&gt;|&lt;SIG&gt; - ADMIN only; creates a 20-seat lab. */
        private void handleAddLab(ClientSession session, String[] parts) {
            if (parts.length != 4) {
                session.send("ADD_LAB_FAIL|BAD_FRAME");
                return;
            }
            if (authorize(session, RoleGuard.Action.ADD_LAB, parts[2], parts[3], "ADD_LAB_FAIL") == null) {
                return;
            }
            String labId = parts[1];
            if (!labId.matches("[A-Z0-9_]{1,16}")) {
                session.send("ADD_LAB_FAIL|INVALID_LAB_ID");
                return;
            }
            try {
                seatStore.addLab(labId, SEATS_PER_LAB);
            } catch (IllegalArgumentException e) {
                session.send("ADD_LAB_FAIL|LAB_EXISTS");
                return;
            }
            NioGatewayServer.log("session#%d lab %s created with %d seats", session.id(), labId, SEATS_PER_LAB);
            session.send("ADD_LAB_OK|" + labId + "|" + SEATS_PER_LAB);
        }

        private void handleList(ClientSession session, String[] parts) {
            if (parts.length < 2) {
                session.send("ERR|UNKNOWN_LAB");
                return;
            }
            if (!isKnownLab(parts[1])) {
                session.send("ERR|UNKNOWN_LAB");
                return;
            }

            StringBuilder response =
                    new StringBuilder("SLOTS|").append(parts[1]).append('|');
            for (int seatId = 1; seatId <= SEATS_PER_LAB; seatId++) {
                if (seatId > 1) {
                    response.append(',');
                }
                String seatName = String.format("S%02d", seatId);
                response.append(seatName).append(':')
                        .append(seatStore.isBooked(parts[1], seatId) ? "BOOKED" : "FREE");
            }
            session.send(response.toString());
        }

        /** BOOK|&lt;labId&gt;|&lt;seatId&gt;|&lt;TOKEN&gt;|&lt;SIG&gt; */
        private void handleBook(ClientSession session, String[] parts) {
            if (parts.length != 5) {
                session.send("BOOK_FAIL|BAD_FRAME");
                return;
            }
            TokenService.Claims claims =
                    authorize(session, RoleGuard.Action.BOOK, parts[3], parts[4], "BOOK_FAIL");
            if (claims == null) {
                return;
            }

            String labId = parts[1];
            Integer seatId = parseSeatId(parts[2]);
            if (!isKnownLab(labId)) {
                session.send("BOOK_FAIL|UNKNOWN_SEAT");
                return;
            }
            if (seatId == null) {
                session.send("BOOK_FAIL|UNKNOWN_SEAT");
                return;
            }

            String userId = claims.userId(); // from the verified token, not from the frame

            try {
                workerPool.submitBooking(
                        seatStore,
                        labId,
                        seatId,
                        userId,
                        receipt -> gateway.execute(() -> sendBookingResult(session, receipt)),
                        throwable -> gateway.execute(() -> {
                            if (session.isOpen()) {
                                session.send("ERR|INTERNAL");
                            }
                            NioGatewayServer.log(
                                    "booking worker failed for session#%d: %s",
                                    session.id(),
                                    throwable);
                        }));
            } catch (RejectedExecutionException e) {
                session.send("ERR|INTERNAL");
            }
        }

        private void sendBookingResult(ClientSession session, BookingReceipt receipt) {
            if (!session.isOpen()) {
                return;
            }
            if (!receipt.isSuccessful()) {
                String result = switch (receipt.getStatus()) {
                    case SEAT_ALREADY_TAKEN -> "SEAT_ALREADY_TAKEN";
                    case INVALID_REQUEST -> "UNKNOWN_SEAT";
                    default -> "INTERNAL";
                };
                session.send("BOOK_FAIL|" + result);
                return;
            }

            String receiptId = receipt.getReceiptId();
            String hash = roleGuard.tokens().signReceipt(receiptId, receipt.getLabId(),
                    String.format("S%02d", receipt.getSeatId()), receipt.getUserId(), receipt.getTimestamp());
            session.send("BOOK_OK|" + receiptId + "|" + hash + "|labsync://localhost:"
                    + DEFAULT_PORT + "/receipt/" + receiptId);
        }

        private boolean isKnownLab(String labId) {
            return seatStore.getLabIds().contains(labId); // includes labs added with ADD_LAB
        }

        private static Integer parseSeatId(String seatName) {
            if (seatName == null
                    || seatName.length() != 3
                    || !seatName.startsWith("S")) {
                return null;
            }
            try {
                int seatId = Integer.parseInt(seatName.substring(1));
                return seatId >= 1 && seatId <= SEATS_PER_LAB ? seatId : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------------- standalone demo

    /**
     * Minimal in-memory LIST/BOOK handler so the gateway can be run and demonstrated on its own.
     * It runs entirely on the reactor thread. At integration it is replaced by the engine's
     * SeatStore + worker pool (Member 2) and token checks (Member 4) via {@link FrameHandler}.
     */
    static final class DemoFrameHandler implements FrameHandler, AutoCloseable {
        private final Map<String, Map<String, String>> labs = new TreeMap<>();
        private final com.labsync.udp.UdpStatusBroadcaster broadcaster;
        private int receiptCounter;

        DemoFrameHandler() {
            com.labsync.udp.UdpStatusBroadcaster b = null;
            try {
                b = new com.labsync.udp.UdpStatusBroadcaster();
            } catch (Exception e) {
                log("WARN Could not initialize UDP status broadcaster: %s", e.getMessage());
            }
            this.broadcaster = b;

            for (String lab : new String[] {"LAB_A", "LAB_B"}) {
                Map<String, String> seats = new TreeMap<>();
                for (int i = 1; i <= 20; i++) {
                    seats.put(String.format("S%02d", i), "FREE");
                }
                labs.put(lab, seats);
            }
        }

        @Override
        public void onFrame(ClientSession session, String frame) {
            String[] parts = frame.split("\\|", -1);
            switch (parts[0]) {
                case "LIST" -> {
                    Map<String, String> seats = parts.length >= 2 ? labs.get(parts[1]) : null;
                    if (seats == null) {
                        session.send("ERR|UNKNOWN_LAB");
                        return;
                    }
                    StringBuilder sb = new StringBuilder("SLOTS|").append(parts[1]).append('|');
                    seats.forEach((seat, status) -> sb.append(seat).append(':').append(status).append(','));
                    sb.setLength(sb.length() - 1);
                    session.send(sb.toString());
                }
                case "BOOK" -> {
                    if (parts.length < 5) {
                        session.send("BOOK_FAIL|BAD_FRAME");
                        return;
                    }
                    Map<String, String> seats = labs.get(parts[1]);
                    String status = seats == null ? null : seats.get(parts[2]);
                    if (status == null) {
                        session.send("BOOK_FAIL|UNKNOWN_SEAT");
                    } else if (!status.equals("FREE")) {
                        session.send("BOOK_FAIL|SEAT_ALREADY_TAKEN");
                    } else {
                        seats.put(parts[2], "BOOKED");
                        String receiptId = String.format("R%05d", ++receiptCounter);
                        session.send("BOOK_OK|" + receiptId + "|UNSIGNED|labsync://localhost:" + DEFAULT_PORT
                                + "/receipt/" + receiptId);

                        // Broadcast live seat state transition over UDP Port 6000 (Member 3)
                        if (broadcaster != null) {
                            try {
                                String user = parts.length > 3 && !parts[3].isBlank() ? parts[3] : "STUDENT";
                                broadcaster.broadcast(parts[1], parts[2], "BOOKED", user);
                            } catch (IOException e) {
                                log("WARN UDP status broadcast failed: %s", e.getMessage());
                            }
                        }
                    }
                }
                default -> session.send("ERR|UNKNOWN_COMMAND");
            }
        }

        @Override
        public void close() {
            if (broadcaster != null) {
                broadcaster.close();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        SeatStore seatStore = new SeatStore(Map.of("LAB_A", 20, "LAB_B", 20));
        BookingWorkerPool workerPool = new BookingWorkerPool(4);
        NioGatewayServer server =
                new NioGatewayServer(port, seatStore, workerPool);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "LabSync-Shutdown"));
        server.start();
        server.awaitTermination();
    }
}
