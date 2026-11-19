package com.labsync.nio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Test client for demonstrating the NIO gateway.
 *
 * <pre>
 *   java -cp bin com.labsync.nio.NioDemoClient shell [host] [port]   interactive terminal client
 *   java -cp bin com.labsync.nio.NioDemoClient multi [host] [port]   4 simultaneous LIST|LAB_A (-Dclients=N)
 *   java -cp bin com.labsync.nio.NioDemoClient split [host] [port]   "BOO" ... 500 ms ... "K|LAB_A|S05|TOK|SIG\n"
 *   java -cp bin com.labsync.nio.NioDemoClient spin  [host] [port]   trigger selector rebuild (server needs -Dlabsync.debug=true)
 * </pre>
 */
public final class NioDemoClient {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "shell";
        String host = args.length > 1 ? args[1] : "localhost";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : NioGatewayServer.DEFAULT_PORT;

        switch (mode) {
            case "shell" -> shell(host, port);
            case "multi" -> multi(host, port, Integer.getInteger("clients", 4));
            case "split" -> split(host, port);
            case "spin" -> single(host, port, "SIMULATE_EPOLL_SPIN");
            default -> System.out.println("usage: NioDemoClient shell|multi|split|spin [host] [port]");
        }
    }

    private static void shell(String host, int port) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            System.out.println("connected to " + socket.getRemoteSocketAddress() + " - type frames, Ctrl+C to quit");
            BufferedReader in = reader(socket);
            Thread printer = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        System.out.println("<< " + line);
                    }
                    System.out.println("server closed the connection");
                } catch (IOException e) {
                    System.out.println("connection lost: " + e.getMessage());
                }
            }, "printer");
            printer.setDaemon(true);
            printer.start();

            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            String line;
            while ((line = stdin.readLine()) != null) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    private static void multi(String host, int port, int count) throws Exception {
        List<Socket> sockets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sockets.add(new Socket(host, port));
        }
        System.out.println(count + " connections open, firing LIST|LAB_A on all at once");

        CountDownLatch gate = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Socket socket = sockets.get(i);
            int n = i + 1;
            Thread t = new Thread(() -> {
                try {
                    BufferedReader in = reader(socket);
                    gate.await();
                    write(socket, "LIST|LAB_A\n");
                    System.out.printf("[client-%d local:%d] << %s%n", n, socket.getLocalPort(), in.readLine());
                } catch (Exception e) {
                    System.out.printf("[client-%d] failed: %s%n", n, e.getMessage());
                }
            });
            threads.add(t);
            t.start();
        }
        gate.countDown();
        for (Thread t : threads) {
            t.join();
        }

        Socket first = sockets.get(0);
        write(first, "STATS\n");
        System.out.println("[client-1] << " + reader(first).readLine());
        for (Socket s : sockets) {
            s.close();
        }
    }

    private static void split(String host, int port) throws Exception {
        try (Socket socket = new Socket(host, port)) {
            socket.setTcpNoDelay(true);
            BufferedReader in = reader(socket);
            System.out.println(">> \"BOO\"   (no newline yet)");
            write(socket, "BOO");
            Thread.sleep(500);
            System.out.println(">> \"K|LAB_A|S05|TOK|SIG\\n\"");
            write(socket, "K|LAB_A|S05|TOK|SIG\n");
            System.out.println("<< " + in.readLine());
        }
    }

    private static void single(String host, int port, String frame) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            write(socket, frame + "\n");
            System.out.println("<< " + reader(socket).readLine());
        }
    }

    private static BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private static void write(Socket socket, String data) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(data.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
