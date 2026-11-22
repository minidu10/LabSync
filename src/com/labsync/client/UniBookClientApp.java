package com.labsync.client;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

/**
 * UniBook desktop client (Member 5).
 *
 * Responsibilities are split so each class is easy to explain:
 *   NetworkResolver   - hostname -> IP, receipt URI parsing
 *   AuthClient        - TLS login on 8443
 *   GatewayConnection - LIST / BOOK over TCP on 5000
 *   SeatGridPanel     - drawing seats (no networking)
 *   this class        - wiring + threading + error messages
 *
 * THREADING RULE: Swing is single-threaded. Anything that touches the network
 * runs in runInBackground(...) (a SwingWorker); its result is handed back to
 * the Event Dispatch Thread, which is the only thread that updates components.
 * So a slow or dead server can never freeze the window.
 */
public class UniBookClientApp extends JFrame {

    private static final long serialVersionUID = 1L;

    enum ConnState {
        OFFLINE("Offline", new Color(198, 40, 40)),
        CONNECTING("Connecting...", new Color(255, 160, 0)),
        RECONNECTING("Reconnecting...", new Color(255, 160, 0)),
        CONNECTED("Connected", new Color(46, 125, 50));

        final String label;
        final Color colour;

        ConnState(String label, Color colour) {
            this.label = label;
            this.colour = colour;
        }
    }

    // ---- networking objects (no Swing inside) ----
    private final GatewayConnection gateway = new GatewayConnection();
    private volatile Session session;
    private volatile String host;

    // ---- connection monitoring ----
    private static final int KEEPALIVE_MS = 3000;
    private static final long MAX_BACKOFF_MS = 8000;
    private final javax.swing.Timer keepAliveTimer = new javax.swing.Timer(KEEPALIVE_MS, e -> keepAlive());
    private final java.util.concurrent.atomic.AtomicBoolean keepAliveRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Thread reconnectThread;

    // ---- UI components ----
    private final JTextField hostField = new JTextField("localhost", 14);
    private final JTextField userField = new JTextField("student1", 9);
    private final JPasswordField passField = new JPasswordField(9);
    private final JButton connectButton = new JButton("Connect");
    private final JComboBox<String> labBox = new JComboBox<>(new String[] { "LAB_A", "LAB_B" });
    private final JButton refreshButton = new JButton("Refresh");
    private final JLabel statusLabel = new JLabel();
    private final JLabel userLabel = new JLabel("Not logged in");
    private final SeatGridPanel grid = new SeatGridPanel();
    private final JTextArea logArea = new JTextArea(8, 60);

    public UniBookClientApp(String initialHost) {
        super("UniBook - Seat & Lab-Slot Booking");
        hostField.setText(initialHost);
        buildLayout();
        wireEvents();
        setState(ConnState.OFFLINE);
        grid.setInteractive(false);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(880, 760);
        setMinimumSize(new java.awt.Dimension(760, 600));
        setLocationRelativeTo(null);
    }

    /** The UDP listener (Member 3) delivers updates to this handler. */
    public SeatUpdateHandler getSeatUpdateHandler() {
        return grid;
    }

    // =====================================================================
    // Layout
    // =====================================================================

    private static final Color HEADER_BG = new Color(33, 47, 61);

    /**
     * Window layout (top to bottom):
     *   header      - app name + connection status pill
     *   connection  - host / user / password / Connect
     *   lab toolbar - lab selector, Refresh, logged-in user badge
     *   centre      - seat grid (top) and network activity log (bottom)
     */
    private void buildLayout() {
        // --- header ---
        JLabel appName = new JLabel("UniBook");
        appName.setFont(appName.getFont().deriveFont(Font.BOLD, 22f));
        appName.setForeground(Color.WHITE);
        JLabel subtitle = new JLabel("  Seat & Lab-Slot Booking");
        subtitle.setForeground(new Color(180, 195, 210));
        JPanel titles = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        titles.setOpaque(false);
        titles.add(appName);
        titles.add(subtitle);

        statusLabel.setOpaque(true);
        statusLabel.setForeground(Color.WHITE);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(HEADER_BG);
        header.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        header.add(titles, BorderLayout.WEST);
        header.add(statusLabel, BorderLayout.EAST);

        // --- connection form ---
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        form.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(8, 8, 0, 8),
                BorderFactory.createTitledBorder("Connection")));
        form.add(new JLabel("Server host"));
        form.add(hostField);
        form.add(new JLabel("User"));
        form.add(userField);
        form.add(new JLabel("Password"));
        form.add(passField);
        connectButton.setFont(connectButton.getFont().deriveFont(Font.BOLD));
        form.add(connectButton);

        // --- lab toolbar ---
        labBox.setEditable(true);                 // admins may add other labs
        userLabel.setFont(userLabel.getFont().deriveFont(Font.BOLD));
        userLabel.setForeground(new Color(90, 90, 90));
        JPanel labLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        labLeft.add(new JLabel("Lab"));
        labLeft.add(labBox);
        labLeft.add(refreshButton);
        JPanel labRow = new JPanel(new BorderLayout());
        labRow.setBorder(BorderFactory.createEmptyBorder(8, 12, 0, 18));
        labRow.add(labLeft, BorderLayout.WEST);
        labRow.add(userLabel, BorderLayout.EAST);

        JPanel top = new JPanel();
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        top.add(header);
        top.add(form);
        top.add(labRow);

        // --- network activity log ---
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JButton clearLog = new JButton("Clear");
        clearLog.addActionListener(e -> logArea.setText(""));
        JLabel logTitle = new JLabel("Network activity");
        logTitle.setFont(logTitle.getFont().deriveFont(Font.BOLD));
        JPanel logHeader = new JPanel(new BorderLayout());
        logHeader.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        logHeader.add(logTitle, BorderLayout.WEST);
        logHeader.add(clearLog, BorderLayout.EAST);
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        logPanel.add(logHeader, BorderLayout.NORTH);
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, grid, logPanel);
        split.setResizeWeight(0.72);
        split.setBorder(null);

        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    private void wireEvents() {
        connectButton.addActionListener(e -> connect());
        passField.addActionListener(e -> connect());            // Enter key
        refreshButton.addActionListener(e -> refresh());
        labBox.addActionListener(e -> {
            if ("comboBoxChanged".equals(e.getActionCommand()) && gateway.isConnected()) {
                refresh();
            }
        });
        grid.setOnSeatClicked(this::bookSeat);
    }

    // =====================================================================
    // User actions - each one: read inputs on EDT -> network in background
    //                          -> update UI on EDT
    // =====================================================================

    /** Connect button: resolve host -> TLS login -> open gateway -> LIST. */
    private void connect() {
        String hostText = hostField.getText().trim();
        String user = userField.getText().trim();
        String pass = new String(passField.getPassword());
        String lab = selectedLab();
        if (hostText.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter host, user and password.");
            return;
        }

        stopReconnect();          // a manual connect replaces any automatic retry
        session = null;           // fresh login; failures now mean "offline", not "reconnect"
        host = null;
        setState(ConnState.CONNECTING);
        connectButton.setEnabled(false);
        log("Resolving '" + hostText + "' ...");

        runInBackground(() -> {
            log("DNS  " + NetworkResolver.describe(hostText));
            InetAddress address = NetworkResolver.resolve(hostText);

            log("TLS  connecting to " + address.getHostAddress() + ":" + AuthClient.DEFAULT_PORT);
            AuthClient.LoginResult login = AuthClient.login(address, AuthClient.DEFAULT_PORT, user, pass);
            log("TLS  " + login.tlsProtocol() + " / " + login.cipherSuite()
                    + " -> AUTH_OK role=" + login.session().role());

            gateway.connect(address, GatewayConnection.DEFAULT_PORT);
            log("TCP  connected to gateway " + address.getHostAddress() + ":" + GatewayConnection.DEFAULT_PORT);

            Map<String, String> seats = gateway.listSeats(lab);
            host = hostText;
            session = login.session();
            return seats;
        }, seats -> {
            setState(ConnState.CONNECTED);
            keepAliveTimer.start();
            userLabel.setText("● " + session.userId() + "  ·  " + session.role());
            userLabel.setForeground(session.isAdmin() ? new Color(142, 36, 170) : SeatGridPanel.MINE);
            grid.setCurrentUser(session.userId());
            showSeats(lab, seats);
        });
    }

    /** LIST the selected lab again. */
    private void refresh() {
        String lab = selectedLab();
        runInBackground(() -> gateway.listSeats(lab), seats -> showSeats(lab, seats));
    }

    /** Called by SeatGridPanel when a FREE seat is clicked. */
    private void bookSeat(String seatId) {
        String lab = grid.getCurrentLab();
        int answer = JOptionPane.showConfirmDialog(this,
                "Book seat " + seatId + " in " + lab + "?", "Confirm booking", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        log("BOOK " + lab + " " + seatId + " ...");
        runInBackground(() -> gateway.book(lab, seatId, session), result -> {
            if (result.ok()) {
                grid.setSeatStatus(seatId, "BOOKED");
                grid.markMine(seatId);
                log("BOOK_OK receipt " + result.receiptId());
                ReceiptDialog.show(this, lab, seatId, result);
            } else {
                log("BOOK_FAIL " + result.reason());
                JOptionPane.showMessageDialog(this, "Booking failed: " + result.reason(),
                        "Booking failed", JOptionPane.WARNING_MESSAGE);
                refresh();   // our map was stale - reload it
            }
        });
    }

    private void showSeats(String lab, Map<String, String> seats) {
        grid.showLab(lab, seats);
        grid.setInteractive(true);
        log("LIST " + lab + ": " + seats.size() + " seats, "
                + seats.values().stream().filter("FREE"::equals).count() + " free");
    }

    private String selectedLab() {
        Object item = labBox.getSelectedItem();
        return item == null ? "LAB_A" : item.toString().trim();
    }

    // =====================================================================
    // Threading + error handling
    // =====================================================================

    /**
     * Runs work on a background thread (SwingWorker). On success, onSuccess
     * runs on the EDT with the result; on failure, handleError runs on the EDT.
     */
    private <T> void runInBackground(Callable<T> work, Consumer<T> onSuccess) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                connectButton.setEnabled(true);
                try {
                    onSuccess.accept(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    handleError(e.getCause());
                }
            }
        }.execute();
    }

    /** Turns exceptions into short, human-readable messages - no stack traces. */
    private void handleError(Throwable t) {
        String message;
        if (t instanceof UnknownHostException) {
            message = "Unknown host: cannot resolve '" + hostField.getText().trim() + "'";
        } else if (t instanceof AuthException) {
            message = "Login rejected: " + t.getMessage();
        } else if (hasCause(t, java.security.GeneralSecurityException.class)) {
            // Java could not load the truststore - a local setup problem, not the network
            message = "TLS setup failed: cannot load the truststore. Check the "
                    + "-Djavax.net.ssl.trustStore path and trustStorePassword.";
        } else if (t instanceof SSLException) {
            message = "Secure connection failed (is the server certificate trusted?)";
        } else if (t instanceof ConnectException) {
            message = "Server not reachable (is it running?)";
        } else if (t instanceof SocketTimeoutException) {
            message = "Server did not respond in time";
        } else if (t instanceof java.net.ProtocolException) {
            message = "Unexpected reply from server: " + t.getMessage();
        } else if (t instanceof IOException) {
            message = "Connection lost: " + t.getMessage();
        } else if (t instanceof IllegalArgumentException) {
            message = "Invalid input: " + t.getMessage();
        } else {
            message = "Unexpected error: " + t;
        }
        log("ERROR " + message);

        if (!gateway.isConnected()) {
            onConnectionLost();
        }
        JOptionPane.showMessageDialog(this, message, "UniBook", JOptionPane.ERROR_MESSAGE);
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    /** Gateway socket is gone. If we were logged in, try to get it back. */
    private void onConnectionLost() {
        grid.setInteractive(false);
        if (session != null && host != null) {
            startReconnect();
        } else {
            keepAliveTimer.stop();
            setState(ConnState.OFFLINE);
        }
    }

    // =====================================================================
    // Connection monitoring and automatic reconnect
    // =====================================================================

    /**
     * Every few seconds (Swing Timer, fires on the EDT) send a LIST in the
     * background. A blocking client only notices a dead server when it next
     * uses the socket, so this is how we detect a crash quickly. It also
     * re-syncs the grid if a UDP update was lost.
     */
    private void keepAlive() {
        if (reconnectThread != null || !gateway.isConnected()
                || !keepAliveRunning.compareAndSet(false, true)) {
            return;
        }
        String lab = grid.getCurrentLab() != null ? grid.getCurrentLab() : selectedLab();
        new SwingWorker<Map<String, String>, Void>() {
            @Override
            protected Map<String, String> doInBackground() throws Exception {
                return gateway.listSeats(lab);
            }

            @Override
            protected void done() {
                keepAliveRunning.set(false);
                try {
                    Map<String, String> seats = get();
                    if (lab.equals(grid.getCurrentLab())) {
                        grid.showLab(lab, seats);           // quiet refresh, no log spam
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    log("Keep-alive failed: " + e.getCause().getClass().getSimpleName()
                            + " - " + e.getCause().getMessage());
                    if (!gateway.isConnected()) {
                        onConnectionLost();                  // silent: no popup
                    }
                }
            }
        }.execute();
    }

    /**
     * Retry loop on its own thread: wait, re-resolve the host, reconnect the
     * gateway, reload the grid. Waits 1s, 2s, 4s, 8s, 8s, ... between tries
     * (exponential backoff) so a dead server is not hammered.
     * The TLS session token is reused, so the user does not log in again.
     */
    private void startReconnect() {
        if (reconnectThread != null) {
            return;                                          // already retrying
        }
        setState(ConnState.RECONNECTING);
        String lab = grid.getCurrentLab() != null ? grid.getCurrentLab() : selectedLab();  // read on EDT
        String targetHost = host;
        Thread t = new Thread(() -> {
            Thread me = Thread.currentThread();
            long delay = 1000;
            int attempt = 0;
            while (me == reconnectThread) {
                attempt++;
                int n = attempt;
                SwingUtilities.invokeLater(() -> statusLabel.setText(
                        "● Reconnecting... (attempt " + n + ")"));
                log("Reconnect attempt " + n + " in " + delay / 1000 + " s");
                try {
                    Thread.sleep(delay);
                    gateway.connect(NetworkResolver.resolve(targetHost), GatewayConnection.DEFAULT_PORT);
                    Map<String, String> seats = gateway.listSeats(lab);
                    SwingUtilities.invokeLater(() -> {
                        if (reconnectThread != me) {
                            return;                          // cancelled meanwhile (manual Connect)
                        }
                        reconnectThread = null;
                        setState(ConnState.CONNECTED);
                        log("Reconnected after " + n + " attempt(s)");
                        showSeats(lab, seats);
                    });
                    return;
                } catch (InterruptedException e) {
                    return;                                  // stopReconnect() was called
                } catch (IOException e) {
                    log("  failed: " + e.getClass().getSimpleName());
                    delay = Math.min(delay * 2, MAX_BACKOFF_MS);
                }
            }
        }, "reconnect");
        t.setDaemon(true);
        reconnectThread = t;
        t.start();
    }

    private void stopReconnect() {
        Thread t = reconnectThread;
        reconnectThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void setState(ConnState state) {
        statusLabel.setText("● " + state.label);
        statusLabel.setBackground(state.colour);     // coloured "pill" in the header
    }

    /** Thread-safe log: may be called from background threads. */
    private void log(String msg) {
        String line = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + msg + "\n";
        SwingUtilities.invokeLater(() -> {
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // =====================================================================

    /**
     * Run with the truststore that trusts the auth server's certificate:
     *   java -Djavax.net.ssl.trustStore=... -Djavax.net.ssl.trustStorePassword=...
     *        -cp bin com.labsync.client.UniBookClientApp [host]
     */
    /** Nimbus ships with the JDK (javax.swing) - a modern look with no extra library. */
    private static void useNimbusLookAndFeel() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    return;
                }
            }
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
            // the default look and feel is fine
        }
    }

    public static void main(String[] args) {
        String initialHost = args.length > 0 ? args[0] : "localhost";
        SwingUtilities.invokeLater(() -> {
            useNimbusLookAndFeel();
            UniBookClientApp app = new UniBookClientApp(initialHost);
            app.setVisible(true);
            // Integration point (Member 3): start the UDP listener here, e.g.
            //   new UdpStatusListener(6000, app.getSeatUpdateHandler()).start();
        });
    }
}
