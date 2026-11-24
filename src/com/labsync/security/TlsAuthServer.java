package com.labsync.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import com.labsync.security.RoleGuard.Role;
import com.labsync.security.TokenService.IssuedToken;

/**
 * TLS authentication server (TCP port 8443), the "control plane" of LabSync.
 *
 * <p>Login runs over a blocking {@link SSLServerSocket}, so the password is always encrypted on
 * the wire. A successful login returns an AES-encrypted, HMAC-SHA256-signed token, which the client
 * then sends with each request to the non-blocking NIO gateway on port 5000. The gateway checks it
 * without any TLS work of its own.
 *
 * <pre>
 *   LOGIN|&lt;userId&gt;|&lt;password&gt;  -&gt;  AUTH_OK|&lt;ROLE&gt;|&lt;TOKEN&gt;|&lt;SHA256_SIG&gt;
 *                               AUTH_FAIL|INVALID_CREDENTIALS
 *                               AUTH_FAIL|TOO_MANY_ATTEMPTS
 * </pre>
 *
 * <p>Hardening:
 * <ul>
 *   <li>the TLS context is built explicitly from a JDK {@link KeyStore} (server certificate and
 *       private key);</li>
 *   <li>only TLSv1.3 and TLSv1.2 are enabled;</li>
 *   <li>a fixed worker pool with a <em>bounded</em> queue serves logins, not one thread per
 *       connection; when the queue is full, new connections are closed at once instead of
 *       piling up;</li>
 *   <li>one request per connection: the server reads one {@code LOGIN}, replies, and closes;</li>
 *   <li>each connection has an overall deadline (default 5 s from accept, covering the TLS
 *       handshake and the request), not a timeout that restarts on every read. Every read uses
 *       only the time left, and a watchdog force-closes connections that trickle bytes to stay
 *       alive. A slow or hostile client therefore holds a worker for a bounded time;</li>
 *   <li>frames are limited to {@value #MAX_FRAME_BYTES} UTF-8 <em>bytes</em> and must be valid
 *       UTF-8. An oversized line is read to its end and discarded, never split into two
 *       requests;</li>
 *   <li>passwords are stored as salted PBKDF2-HMAC-SHA256 hashes, and unknown users cost the same
 *       hashing work as known ones;</li>
 *   <li>after {@value #MAX_FAILURES} wrong passwords a user ID is locked for 30 seconds;</li>
 *   <li>passwords are never logged.</li>
 * </ul>
 *
 * <p>Run (from the repository root, after {@code dev/gen-certs.sh}):
 * {@code java -cp bin com.labsync.security.TlsAuthServer [port]}. Optional settings:
 * {@code -Dlabsync.keystore=<file>} and {@code -Dlabsync.keystorePassword=<pw>} (they fall back to
 * {@code javax.net.ssl.keyStore} / {@code keyStorePassword}, then {@code dev/certs/server.jks} /
 * {@code changeit}), and {@code -Dlabsync.secret=<shared secret>} (see {@link SharedSecret}).
 */
public final class TlsAuthServer {

    public static final int DEFAULT_PORT = 8443;
    static final int MAX_FRAME_BYTES = 512;
    static final int MAX_FAILURES = 5;
    static final Duration LOCKOUT = Duration.ofSeconds(30);
    /** Login threads. More than a handful of idle clients is needed to saturate them. */
    static final int DEFAULT_WORKER_THREADS = 16;
    /** Accepted connections allowed to wait for a worker; beyond this they are closed. */
    static final int DEFAULT_QUEUE_CAPACITY = 32;
    /** Whole-connection budget from accept: queueing + TLS handshake + LOGIN frame. */
    static final Duration DEFAULT_CONNECTION_DEADLINE = Duration.ofSeconds(5);
    /** The watchdog fires this long after the deadline, as a backstop for trickling clients. */
    private static final long WATCHDOG_GRACE_MS = 500;
    /** Bytes of an oversized line read and discarded (so the reply is not lost to a TCP reset). */
    static final int MAX_DRAIN_BYTES = 8 * 1024;
    private static final String[] PREFERRED_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final int requestedPort;
    private final SSLContext sslContext;
    private final TokenService tokens;
    private final UserDirectory users;
    private final Clock clock;
    private final int workerThreads;
    private final int queueCapacity;
    private final Duration connectionDeadline;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    private volatile SSLServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;
    private ThreadPoolExecutor workers;
    private ScheduledThreadPoolExecutor watchdog;

    public TlsAuthServer(int port, SSLContext sslContext, TokenService tokens, UserDirectory users) {
        this(port, sslContext, tokens, users, Clock.systemUTC());
    }

    TlsAuthServer(int port, SSLContext sslContext, TokenService tokens, UserDirectory users, Clock clock) {
        this(port, sslContext, tokens, users, clock,
                DEFAULT_WORKER_THREADS, DEFAULT_QUEUE_CAPACITY, DEFAULT_CONNECTION_DEADLINE);
    }

    /** Full constructor; tests use small pools and short deadlines to exercise saturation. */
    TlsAuthServer(int port, SSLContext sslContext, TokenService tokens, UserDirectory users, Clock clock,
            int workerThreads, int queueCapacity, Duration connectionDeadline) {
        if (workerThreads < 1 || queueCapacity < 1 || connectionDeadline.toMillis() < 1) {
            throw new IllegalArgumentException("workers, queue capacity and deadline must be positive");
        }
        this.requestedPort = port;
        this.sslContext = sslContext;
        this.tokens = tokens;
        this.users = users;
        this.clock = clock;
        this.workerThreads = workerThreads;
        this.queueCapacity = queueCapacity;
        this.connectionDeadline = connectionDeadline;
    }

    // ------------------------------------------------------------------------------ TLS setup

    /** Builds a server-side TLS context from a keystore (JKS or PKCS12, detected automatically). */
    public static SSLContext serverContext(Path keystore, char[] password)
            throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(keystore.toFile(), password);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    // ------------------------------------------------------------------------------ lifecycle

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        SSLServerSocket ss = (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(requestedPort), 50);
        ss.setEnabledProtocols(supported(ss.getSupportedProtocols()));
        ss.setNeedClientAuth(false); // clients authenticate with a password, inside the tunnel

        AtomicInteger n = new AtomicInteger();
        // Fixed size, bounded queue, AbortPolicy: a full queue throws RejectedExecutionException,
        // which acceptLoop turns into an immediate close (never an unbounded backlog).
        workers = new ThreadPoolExecutor(workerThreads, workerThreads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), r -> {
                    Thread t = new Thread(r, "LabSync-Auth-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        watchdog = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "LabSync-Auth-Watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.setRemoveOnCancelPolicy(true); // finished connections leave no cancelled tasks behind
        serverSocket = ss;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "LabSync-Auth-Acceptor");
        acceptThread.start();
        log("TLS auth server listening on %s | protocols=%s | users=%d | token fingerprint=%s",
                ss.getLocalSocketAddress(), Arrays.toString(ss.getEnabledProtocols()),
                users.size(), tokens.fingerprint());
        log("limits: %d workers, queue %d, %d ms per connection, %d-byte frames, 1 LOGIN per connection",
                workerThreads, queueCapacity, connectionDeadline.toMillis(), MAX_FRAME_BYTES);
    }

    public void stop() {
        running = false;
        try {
            SSLServerSocket ss = serverSocket;
            if (ss != null) {
                ss.close(); // unblocks accept()
            }
        } catch (IOException ignored) {
            // shutting down
        }
        if (workers != null) {
            workers.shutdownNow();
        }
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
    }

    public void awaitTermination() throws InterruptedException {
        Thread t = acceptThread;
        if (t != null) {
            t.join();
        }
        if (workers != null) {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (watchdog != null) {
            watchdog.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** The bound port (useful when started on port 0 in tests). */
    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            SSLSocket socket;
            try {
                socket = (SSLSocket) serverSocket.accept();
            } catch (IOException e) {
                if (running) {
                    log("accept failed: %s", e.getMessage());
                }
                continue;
            }
            // The deadline starts at accept, so time spent queued for a worker counts too.
            long deadline = System.nanoTime() + connectionDeadline.toNanos();
            String peer = String.valueOf(socket.getRemoteSocketAddress());
            ScheduledFuture<?> killer = watchdog.schedule(() -> forceClose(socket, peer),
                    connectionDeadline.toMillis() + WATCHDOG_GRACE_MS, TimeUnit.MILLISECONDS);
            try {
                workers.execute(() -> handle(socket, peer, deadline, killer));
            } catch (RejectedExecutionException e) {
                killer.cancel(false);
                log("server busy (%d workers, queue %d full): refused %s", workerThreads, queueCapacity, peer);
                closeQuietly(socket);
            }
        }
        log("TLS auth server stopped");
    }

    // ----------------------------------------------------------------------- per connection

    /**
     * Serves exactly one request: handshake, read one LOGIN frame, reply, close. Every blocking
     * step is limited by the time left until {@code deadline}, and the socket is always closed,
     * so the worker is always released.
     */
    private void handle(SSLSocket socket, String peer, long deadline, ScheduledFuture<?> killer) {
        try (socket) {
            applyRemainingTime(socket, deadline);
            socket.startHandshake(); // fail fast on plaintext / untrusted clients
            SSLSession tls = socket.getSession();
            log("TLS handshake OK with %s | %s | %s", peer, tls.getProtocol(), tls.getCipherSuite());

            Frame frame = readFrame(socket, deadline);
            String reply = switch (frame.status()) {
                case OK -> process(frame.text(), peer);
                case TOO_LONG -> "ERR|FRAME_TOO_LONG";
                case MALFORMED -> "ERR|MALFORMED_FRAME";
                case END_OF_STREAM -> null; // client left without a complete frame
            };
            if (reply == null) {
                log("%s closed the connection without a complete frame", peer);
            } else {
                if (frame.status() != FrameStatus.OK) {
                    log("%s sent a bad frame -> %s", peer, reply);
                }
                OutputStream out = socket.getOutputStream();
                out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            // One request per connection: closing here releases the worker for the next client.
        } catch (SocketTimeoutException e) {
            log("%s exceeded the %d ms connection deadline, closed", peer, connectionDeadline.toMillis());
        } catch (SSLException e) {
            log("TLS handshake/record error from %s: %s", peer, e.getMessage());
        } catch (SocketException e) {
            log("%s disconnected: %s", peer, e.getMessage());
        } catch (IOException e) {
            log("I/O error with %s: %s", peer, e.getMessage());
        } catch (RuntimeException e) {
            log("unexpected error with %s: %s", peer, e); // never let one client kill a worker
        } finally {
            killer.cancel(false);
        }
    }

    /** Watchdog action: unblocks a worker stuck on a client that trickles bytes past the deadline. */
    private static void forceClose(SSLSocket socket, String peer) {
        if (!socket.isClosed()) {
            log("%s still open after its connection deadline - force-closing", peer);
            closeQuietly(socket);
        }
    }

    /**
     * Sets the socket read timeout to the time left before {@code deadline}, so a run of slow
     * reads can never add up to more than the deadline.
     *
     * @throws SocketTimeoutException if the deadline has already passed
     */
    private static void applyRemainingTime(SSLSocket socket, long deadline) throws IOException {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remainingMs <= 0) {
            throw new SocketTimeoutException("connection deadline exceeded");
        }
        socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMs)); // > 0: never "infinite"
    }

    /** Handles one decrypted frame and returns the reply (without '\n'). */
    String process(String frame, String peer) {
        String[] p = frame.split("\\|", -1);
        if (!"LOGIN".equals(p[0])) {
            return "ERR|UNKNOWN_COMMAND";
        }
        if (p.length != 3 || p[1].isEmpty()) {
            return "AUTH_FAIL|INVALID_CREDENTIALS";
        }
        String userId = p[1];
        char[] password = p[2].toCharArray();
        try {
            Failures f = failures.get(userId);
            if (f != null && f.lockedUntil != null && clock.instant().isBefore(f.lockedUntil)) {
                log("LOGIN %s from %s -> AUTH_FAIL (locked until %s)", userId, peer, f.lockedUntil);
                return "AUTH_FAIL|TOO_MANY_ATTEMPTS";
            }
            Role role = users.authenticate(userId, password);
            if (role == null) {
                int count = recordFailure(userId);
                log("LOGIN %s from %s -> AUTH_FAIL (wrong credentials, %d/%d)", userId, peer, count, MAX_FAILURES);
                return "AUTH_FAIL|INVALID_CREDENTIALS";
            }
            failures.remove(userId);
            IssuedToken issued = tokens.issue(userId, role);
            log("LOGIN %s from %s -> AUTH_OK role=%s, token expires %s, sig=%s...",
                    userId, peer, role, issued.claims().expiresAt(), issued.signature().substring(0, 12));
            return "AUTH_OK|" + role + "|" + issued.token() + "|" + issued.signature();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private int recordFailure(String userId) {
        Failures f = failures.compute(userId, (k, old) -> {
            Failures next = old == null ? new Failures() : old;
            if (next.lockedUntil != null && !clock.instant().isBefore(next.lockedUntil)) {
                next.count = 0;            // previous lock expired: start counting again
                next.lockedUntil = null;
            }
            next.count++;
            if (next.count >= MAX_FAILURES) {
                next.lockedUntil = clock.instant().plus(LOCKOUT);
            }
            return next;
        });
        return f.count;
    }

    private static final class Failures {
        int count;
        Instant lockedUntil;
    }

    enum FrameStatus { OK, TOO_LONG, MALFORMED, END_OF_STREAM }

    /** Result of reading one frame; {@code text} is set only when the status is OK. */
    record Frame(FrameStatus status, String text) { }

    /**
     * Reads one '\n'-terminated frame from {@code socket}, enforcing the limit in UTF-8 bytes.
     *
     * <ul>
     *   <li>The limit counts raw bytes before decoding, so U+00E9 'e-acute' (2 bytes) and U+2713
     *       'check mark' (3 bytes) count as 2 and 3, not 1. An optional trailing '\r' counts too.</li>
     *   <li>Once the limit is passed, the rest of the line is read and discarded (up to
     *       {@value #MAX_DRAIN_BYTES} bytes, within the deadline) and TOO_LONG is returned. The
     *       oversized frame is never handed on, and its tail is never read as another request. If
     *       unread bytes were left in the socket, closing it could send a TCP reset that destroys
     *       the error reply before the client reads it.</li>
     *   <li>Bytes are decoded strictly: invalid UTF-8 gives MALFORMED instead of being replaced
     *       silently.</li>
     * </ul>
     */
    static Frame readFrame(SSLSocket socket, long deadline) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        boolean tooLong = false;
        int discarded = 0;
        while (true) {
            applyRemainingTime(socket, deadline);
            int b = in.read();
            if (b == -1) {
                return new Frame(tooLong ? FrameStatus.TOO_LONG : FrameStatus.END_OF_STREAM, null);
            }
            if (b == '\n') {
                break;
            }
            if (tooLong) {
                if (++discarded >= MAX_DRAIN_BYTES) {
                    break; // stop draining; the connection is closed after the reply anyway
                }
            } else if (line.size() == MAX_FRAME_BYTES) {
                tooLong = true; // byte number MAX_FRAME_BYTES + 1: reject the whole frame
                discarded = 1;
            } else {
                line.write(b);
            }
        }
        if (tooLong) {
            return new Frame(FrameStatus.TOO_LONG, null);
        }
        byte[] bytes = line.toByteArray();
        int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length))
                    .toString();
            return new Frame(FrameStatus.OK, text);
        } catch (CharacterCodingException e) {
            return new Frame(FrameStatus.MALFORMED, null);
        }
    }

    private static String[] supported(String[] available) {
        List<String> enabled = new ArrayList<>();
        List<String> have = Arrays.asList(available);
        for (String p : PREFERRED_PROTOCOLS) {
            if (have.contains(p)) {
                enabled.add(p);
            }
        }
        if (enabled.isEmpty()) {
            throw new IllegalStateException("JDK supports neither TLSv1.3 nor TLSv1.2");
        }
        return enabled.toArray(new String[0]);
    }

    private static void closeQuietly(SSLSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    static void log(String format, Object... args) {
        System.out.printf("%s [%s] %s%n", LocalTime.now().format(TIME),
                Thread.currentThread().getName(), String.format(format, args));
    }

    // ----------------------------------------------------------------------------- user table

    /**
     * userId -> (role, salted password hash). Only hashes are stored: the demo passwords
     * (student1/pass123, student2/pass123, admin/admin123) do not appear anywhere in the code.
     * New hashes: {@code java -cp bin com.labsync.security.SecurityUtils <password>}.
     */
    public static final class UserDirectory {

        private record Entry(Role role, String passwordHash) { }

        /** Hash used for unknown users, so "no such user" takes as long as "wrong password". */
        private static final String DUMMY_HASH = SecurityUtils.hashPassword("dummy".toCharArray());

        private final Map<String, Entry> users = new ConcurrentHashMap<>();

        public static UserDirectory demoUsers() {
            UserDirectory d = new UserDirectory();
            d.addHashed("student1", Role.STUDENT,
                    "7be664f30279cdff726b36c1c92ea30f:b4631bd00533cb470dbf3a452c8bcfa368abb05ad9434b685a320cc773b5018f");
            d.addHashed("student2", Role.STUDENT,
                    "bdd75d290042fcedb9adbe3e87390932:f79ce882edcca855a7133dbfc86ae598ee0b2531704e79fd6ca70a5972d7bae7");
            d.addHashed("admin", Role.ADMIN,
                    "90ad256e47fa7a35d4a9db198c8ed241:6a575c83962c8a46582e31892f8497db8f7a01bf5aeb58eb960be251258b13b1");
            return d;
        }

        public void addHashed(String userId, Role role, String saltColonHash) {
            users.put(userId, new Entry(role, saltColonHash));
        }

        public void add(String userId, Role role, char[] password) {
            addHashed(userId, role, SecurityUtils.hashPassword(password));
        }

        /** @return the user's role, or {@code null} for an unknown user or wrong password */
        public Role authenticate(String userId, char[] password) {
            Entry e = users.get(userId);
            boolean ok = SecurityUtils.verifyPassword(password, e == null ? DUMMY_HASH : e.passwordHash());
            return ok && e != null ? e.role() : null;
        }

        public int size() {
            return users.size();
        }
    }

    // ---------------------------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String keystore = firstSet(System.getProperty("labsync.keystore"),
                System.getProperty("javax.net.ssl.keyStore"), "dev/certs/server.jks");
        String password = firstSet(System.getProperty("labsync.keystorePassword"),
                System.getProperty("javax.net.ssl.keyStorePassword"), "changeit");
        Path ks = Paths.get(keystore);
        if (!Files.isRegularFile(ks)) {
            System.err.println("Keystore not found: " + ks.toAbsolutePath()
                    + "\nCreate one with dev/gen-certs.sh, or pass -Dlabsync.keystore=<file>.");
            System.exit(1);
        }

        TlsAuthServer server = new TlsAuthServer(port, serverContext(ks, password.toCharArray()),
                TokenService.fromEnvironment(), UserDirectory.demoUsers());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "LabSync-Auth-Shutdown"));
        server.start();
        log("keystore %s loaded", ks.toAbsolutePath());
        server.awaitTermination();
    }

    private static String firstSet(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }
}
