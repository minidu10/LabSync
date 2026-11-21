package com.labsync.udp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Interactive test and demonstration runner for Member 3's UDP Live Status Feed.
 *
 * <p>Directly implements the execution steps required by the <b>2-Minute Physical Evaluation Demo Script</b>:
 * <ol>
 *   <li><b>Execution & Setup (0:00 - 0:40):</b> Starts side-by-side listeners bound to Port 6000 via {@code SO_REUSEADDR}.</li>
 *   <li><b>Instant Sync Demo (0:40 - 1:15):</b> Dispatches live broadcast frames and demonstrates immediate listener notification.</li>
 *   <li><b>Out-of-Order Rejection Demo (1:15 - 2:00):</b> Injects an out-of-order packet ({@code SEQ:3} after {@code SEQ:7}) and
 *       verifies diagnostic logs confirming {@code incomingSeq <= lastSeenSeq} triggers an immediate drop.</li>
 * </ol>
 *
 * <p><b>Usage:</b>
 * <pre>
 *   java -cp bin com.labsync.udp.UdpDemoClient listen [port]              - Launch standalone listener
 *   java -cp bin com.labsync.udp.UdpDemoClient broadcast [lab] [seat]    - Broadcast next sequential update
 *   java -cp bin com.labsync.udp.UdpDemoClient demo-ooo [port]            - Automated out-of-order rejection demo
 *   java -cp bin com.labsync.udp.UdpDemoClient interactive [port]         - Interactive oral presentation menu
 * </pre>
 */
public final class UdpDemoClient {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0].toLowerCase() : "interactive";
        int port = args.length > 1 && args[1].matches("\\d+") ? Integer.parseInt(args[1]) : UdpStatusBroadcaster.DEFAULT_PORT;

        switch (mode) {
            case "listen" -> runListener(port);
            case "broadcast" -> {
                String lab = args.length > 1 ? args[1] : "LAB_A";
                String seat = args.length > 2 ? args[2] : "S01";
                String status = args.length > 3 ? args[3] : "BOOKED";
                String user = args.length > 4 ? args[4] : "student101";
                runSingleBroadcast(lab, seat, status, user, port);
            }
            case "demo-ooo" -> runOutOfOrderDemo(port);
            case "interactive" -> runInteractiveMenu(port);
            default -> {
                System.out.println("Unknown mode: " + mode);
                System.out.println("Usage: UdpDemoClient listen|broadcast|demo-ooo|interactive [args...]");
            }
        }
    }

    private static void runListener(int port) throws Exception {
        System.out.println("=================================================================");
        System.out.println("  LabSync UDP Status Listener - Demo Client");
        System.out.println("  Binding to UDP Port: " + port + " (SO_REUSEADDR enabled)");
        System.out.println("=================================================================");

        UdpStatusListener listener = new UdpStatusListener(port);
        listener.addStatusListener(update -> {
            System.out.printf("  [UI SEAT-GRID REFRESH] Received update: %s | Seat: %s -> Status: %s (Reserved by: %s)%n",
                    update.labId(), update.seatId(), update.status(), update.userId());
        });

        listener.start();
        System.out.println("Listening for datagrams. Press Enter or Ctrl+C to terminate...");
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        listener.stop();
    }

    private static void runSingleBroadcast(String lab, String seat, String status, String user, int port) throws Exception {
        try (UdpStatusBroadcaster broadcaster = new UdpStatusBroadcaster(port)) {
            SeatStatusUpdate update = broadcaster.broadcast(lab, seat, status, user);
            System.out.printf("Successfully broadcast frame: %s to port %d%n", update.toFrame(), port);
        }
    }

    /**
     * Executes the mandated Out-of-Order Rejection demonstration (Script step 3: 1:15 - 2:00).
     */
    private static void runOutOfOrderDemo(int port) throws Exception {
        System.out.println("=================================================================");
        System.out.println("  MEMBER 3: Out-of-Order Packet Loss Mitigation Demonstration");
        System.out.println("=================================================================");
        System.out.println("1. Starting internal client listener on port " + port + "...");

        try (UdpStatusListener listener = new UdpStatusListener(port);
             UdpStatusBroadcaster broadcaster = new UdpStatusBroadcaster(port)) {

            listener.addStatusListener(u -> System.out.println("   [GUI OBSERVER] Valid update rendered on UI: " + u));
            listener.start();
            Thread.sleep(300);

            System.out.println("\n2. [Step A] Sending valid high-sequence packet: SEQ:7 | LAB_A | S12 | BOOKED | student101");
            broadcaster.broadcastCustom(7, "LAB_A", "S12", "BOOKED", "student101");
            Thread.sleep(800);

            System.out.println("\n3. [Step B] Simulating delayed/out-of-order network arrival: transmitting SEQ:3");
            System.out.println("   (Expected outcome: Listener MUST discard SEQ:3 because incomingSeq (3) <= lastSeenSeq (7))");
            broadcaster.broadcastCustom(3, "LAB_A", "S12", "FREE", "SYSTEM");
            Thread.sleep(800);

            System.out.println("\n4. [Step C] Sending valid subsequent packet: SEQ:8 | LAB_A | S12 | RESERVED | admin");
            broadcaster.broadcastCustom(8, "LAB_A", "S12", "RESERVED", "admin");
            Thread.sleep(800);

            System.out.println("\n=================================================================");
            System.out.printf("  DEMO RESULTS: Total Received=%d | Accepted=%d | Dropped=%d | Final Seq=%d%n",
                    listener.getTotalReceived(), listener.getAcceptedPackets(),
                    listener.getDroppedPackets(), listener.getLastSeenSeq());
            if (listener.getDroppedPackets() > 0) {
                System.out.println("  STATUS: SUCCESS - Out-of-order datagram was correctly dropped.");
                System.out.println("  UI protected against zombie rollback states!");
            } else {
                System.out.println("  STATUS: Check network configuration.");
            }
            System.out.println("=================================================================");
        }
    }

    private static void runInteractiveMenu(int port) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try (UdpStatusBroadcaster broadcaster = new UdpStatusBroadcaster(port)) {
            System.out.println("\n============================================================");
            System.out.println("   LabSync Member 3 - UDP Live Status Evaluation Console");
            System.out.println("   Target Broadcast Port: " + port);
            System.out.println("============================================================");

            while (true) {
                System.out.println("\nSelect an action for the practical demonstration:");
                System.out.println("  [1] Send regular sequential booking broadcast (e.g. SEQ:1, SEQ:2...)");
                System.out.println("  [2] Send simulated out-of-order packet (SEQ:3 after SEQ:7)");
                System.out.println("  [3] Send seat cancellation broadcast (FREE)");
                System.out.println("  [4] Run full automated Out-of-Order Rejection Demo");
                System.out.println("  [5] Exit");
                System.out.print("Choice > ");

                String line = reader.readLine();
                if (line == null || line.trim().equals("5")) {
                    break;
                }

                switch (line.trim()) {
                    case "1" -> {
                        System.out.print("Enter Seat ID [default S01]: ");
                        String seat = reader.readLine().trim();
                        if (seat.isEmpty()) seat = "S01";
                        SeatStatusUpdate up = broadcaster.broadcast("LAB_A", seat, "BOOKED", "student101");
                        System.out.println(">> Broadcast sent: " + up.toFrame());
                    }
                    case "2" -> {
                        System.out.println("Injecting SEQ:7 followed by out-of-order SEQ:3...");
                        broadcaster.broadcastCustom(7, "LAB_A", "S12", "BOOKED", "student101");
                        Thread.sleep(400);
                        broadcaster.broadcastCustom(3, "LAB_A", "S12", "FREE", "SYSTEM");
                        System.out.println(">> Check client listener logs to observe the packet drop!");
                    }
                    case "3" -> {
                        System.out.print("Enter Seat ID [default S01]: ");
                        String seat = reader.readLine().trim();
                        if (seat.isEmpty()) seat = "S01";
                        SeatStatusUpdate up = broadcaster.broadcast("LAB_A", seat, "FREE", "SYSTEM");
                        System.out.println(">> Cancellation broadcast sent: " + up.toFrame());
                    }
                    case "4" -> runOutOfOrderDemo(port);
                    default -> System.out.println("Invalid option.");
                }
            }
        }
    }
}

