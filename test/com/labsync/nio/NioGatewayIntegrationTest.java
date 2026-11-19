package com.labsync.nio;

import com.labsync.engine.BookingWorkerPool;
import com.labsync.engine.SeatStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class NioGatewayIntegrationTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        int port = findFreePort();
        SeatStore seatStore = new SeatStore(Map.of("LAB_A", 20, "LAB_B", 20));
        BookingWorkerPool workerPool = new BookingWorkerPool(4);
        NioGatewayServer server =
                new NioGatewayServer(port, seatStore, workerPool);

        try {
            testServerStarts(server, port);
            testValidBooking(server, port);
            testAsyncProcessing(server, port);
            testSameSeatRace(server, port);
            testSelectorResponsive(server, port);
            testDifferentSeats(server, port);
        } finally {
            testGracefulShutdown(server, workerPool);
        }

        System.out.println();
        System.out.printf("RESULT: %d PASS, %d FAIL%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testServerStarts(NioGatewayServer server, int port)
            throws Exception {
        server.start();
        boolean starts = false;
        try (Socket socket = connect(port)) {
            starts = socket.isConnected();
        }
        check("1. NIO gateway server starts successfully",
                starts,
                "gateway accepts a TCP client connection");
    }

    private static void testValidBooking(NioGatewayServer server, int port)
            throws Exception {
        String response = exchange(port,
                "BOOK|LAB_A|S03|integration-user|TOK|SIG");

        check("2. Client sends a valid booking request",
                response != null,
                "a response is received for BOOK|LAB_A|S03|...");
        check("4. Successful booking returns BOOK_OK with receipt ID",
                response.startsWith("BOOK_OK|")
                        && response.split("\\|").length >= 3
                        && response.contains("|UNSIGNED|labsync://"),
                "BOOK_OK|<receiptId>|UNSIGNED|labsync://...");
    }

    private static void testAsyncProcessing(NioGatewayServer server, int port)
            throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<String> booking = new AtomicReference<>();
        AtomicReference<String> stats = new AtomicReference<>();

        try {
            Future<?> bookingClient = clients.submit(() ->
                    sendWithLatch(port,
                            "BOOK|LAB_A|S04|async-user|TOK|SIG",
                            ready,
                            start,
                            booking));
            Future<?> statsClient = clients.submit(() ->
                    sendWithLatch(port,
                            "STATS",
                            ready,
                            start,
                            stats));

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            bookingClient.get(5, TimeUnit.SECONDS);
            statsClient.get(5, TimeUnit.SECONDS);
        } finally {
            clients.shutdown();
            clients.awaitTermination(5, TimeUnit.SECONDS);
        }

        check("3. Booking worker pool processes booking asynchronously",
                booking.get() != null
                        && booking.get().startsWith("BOOK_OK|")
                        && stats.get() != null
                        && stats.get().startsWith("STATS|"),
                "booking returns BOOK_OK and STATS remains responsive");
    }

    private static void testSameSeatRace(NioGatewayServer server, int port)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService clients = Executors.newFixedThreadPool(2);
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        try {
            Future<?> firstClient = clients.submit(() ->
                    sendWithLatch(port,
                            "BOOK|LAB_A|S05|race-user-1|TOK|SIG",
                            ready,
                            start,
                            first));
            Future<?> secondClient = clients.submit(() ->
                    sendWithLatch(port,
                            "BOOK|LAB_A|S05|race-user-2|TOK|SIG",
                            ready,
                            start,
                            second));

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            firstClient.get(5, TimeUnit.SECONDS);
            secondClient.get(5, TimeUnit.SECONDS);
        } finally {
            clients.shutdown();
            clients.awaitTermination(5, TimeUnit.SECONDS);
        }

        int okCount = countResponse(first.get(), "BOOK_OK|")
                + countResponse(second.get(), "BOOK_OK|");
        int failCount = countResponse(first.get(),
                "BOOK_FAIL|SEAT_ALREADY_TAKEN")
                + countResponse(second.get(),
                        "BOOK_FAIL|SEAT_ALREADY_TAKEN");

        check("5. Same-seat race returns one BOOK_OK and one duplicate failure",
                okCount == 1 && failCount == 1,
                "one response starts with BOOK_OK and one equals "
                        + "BOOK_FAIL|SEAT_ALREADY_TAKEN");
    }

    private static void testSelectorResponsive(NioGatewayServer server, int port)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService clients = Executors.newFixedThreadPool(5);

        try {
            List<Future<String>> bookings = new ArrayList<>();
            for (int seatId = 10; seatId <= 13; seatId++) {
                int seat = seatId;
                bookings.add(clients.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return exchange(port,
                            "BOOK|LAB_A|S"
                                    + String.format("%02d", seat)
                                    + "|responsive-" + seat
                                    + "|TOK|SIG");
                }));
            }

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            Future<String> stats = clients.submit(() -> exchange(port, "STATS"));
            String statsResponse =
                    stats.get(5, TimeUnit.SECONDS);

            boolean allBookingsReturned = true;
            for (Future<String> booking : bookings) {
                allBookingsReturned =
                        allBookingsReturned
                                && booking.get(5, TimeUnit.SECONDS)
                                        .startsWith("BOOK_OK|");
            }

            check("6. Selector stays responsive while booking tasks execute",
                    statsResponse.startsWith("STATS|")
                            && allBookingsReturned,
                    "STATS responds and concurrent booking Futures complete");
        } finally {
            clients.shutdown();
            clients.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void testDifferentSeats(NioGatewayServer server, int port)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService clients = Executors.newFixedThreadPool(3);
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        AtomicReference<String> third = new AtomicReference<>();

        try {
            Future<?> firstClient = clients.submit(() ->
                    sendWithLatch(port,
                            "BOOK|LAB_B|S14|different-1|TOK|SIG",
                            ready,
                            start,
                            first));
            Future<?> secondClient = clients.submit(() ->
                    sendWithLatch(port,
                            "BOOK|LAB_B|S15|different-2|TOK|SIG",
                            ready,
                            start,
                            second));
            Future<?> thirdClient = clients.submit(() ->
                    sendWithLatch(port,
                            "BOOK|LAB_B|S16|different-3|TOK|SIG",
                            ready,
                            start,
                            third));

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            firstClient.get(5, TimeUnit.SECONDS);
            secondClient.get(5, TimeUnit.SECONDS);
            thirdClient.get(5, TimeUnit.SECONDS);
        } finally {
            clients.shutdown();
            clients.awaitTermination(5, TimeUnit.SECONDS);
        }

        check("7. Multiple clients book different seats concurrently",
                first.get().startsWith("BOOK_OK|")
                        && second.get().startsWith("BOOK_OK|")
                        && third.get().startsWith("BOOK_OK|"),
                "all three clients receive BOOK_OK");
    }

    private static void testGracefulShutdown(NioGatewayServer server,
            BookingWorkerPool workerPool)
            throws Exception {
        server.stop();
        server.awaitTermination();
        boolean poolTerminated =
                workerPool.awaitTermination(5, TimeUnit.SECONDS);

        check("8. Server shuts down and terminates worker pool",
                workerPool.isShutdown()
                        && poolTerminated
                        && workerPool.isTerminated()
                        && !isPortOpen(getServerPort(server)),
                "server terminates and BookingWorkerPool reaches terminated state");
    }

    private static void sendWithLatch(int port, String request,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicReference<String> response) {
        ready.countDown();
        try {
            start.await(5, TimeUnit.SECONDS);
            response.set(exchange(port, request));
        } catch (Exception exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        }
    }

    private static String exchange(int port, String request)
            throws IOException {
        try (Socket socket = connect(port);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(
                             socket.getInputStream(),
                             StandardCharsets.UTF_8));
             OutputStream output = socket.getOutputStream()) {
            output.write((request + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            return reader.readLine();
        }
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(5000);
        return socket;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int getServerPort(NioGatewayServer server) {
        try {
            java.lang.reflect.Field portField =
                    NioGatewayServer.class.getDeclaredField("port");
            portField.setAccessible(true);
            return portField.getInt(server);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static boolean isPortOpen(int port) {
        try (Socket socket = connect(port)) {
            return socket.isConnected();
        } catch (IOException expected) {
            return false;
        }
    }

    private static int countResponse(String response, String prefix) {
        return response != null && response.startsWith(prefix) ? 1 : 0;
    }

    private static void check(String testName, boolean condition,
            String expected) {
        if (condition) {
            passed++;
            System.out.println("PASS: " + testName);
        } else {
            failed++;
            System.out.println("FAIL: " + testName);
            System.out.println("      Expected: " + expected);
        }
    }
}
