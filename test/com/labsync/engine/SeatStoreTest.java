package com.labsync.engine;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class SeatStoreTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws InterruptedException {
        testAvailableSeatBooking();
        testSameSeatRejected();
        testDifferentSeatsConcurrent();
        testManyThreadsSameSeat();

        System.out.println();
        System.out.printf("RESULT: %d PASS, %d FAIL%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testAvailableSeatBooking() {
        SeatStore store = new SeatStore(Map.of("LAB_A", 2));
        boolean booked = store.bookSeat("LAB_A", 1, "user-1");
        boolean ownerMatches = "user-1".equals(store.getBookedUserId("LAB_A", 1));

        check("1. Available seat is booked successfully",
                booked && ownerMatches,
                "bookSeat=true and bookedUserId=user-1");
    }

    private static void testSameSeatRejected() {
        SeatStore store = new SeatStore(Map.of("LAB_A", 2));

        boolean first = store.bookSeat("LAB_A", 1, "user-1");
        boolean second = store.bookSeat("LAB_A", 1, "user-2");
        String owner = store.getBookedUserId("LAB_A", 1);

        check("2. Second user cannot book the same seat",
                first && !second,
                "first=true, second=false");
        check("3. Same-seat booking returns failure and preserves first owner",
                !second && "user-1".equals(owner),
                "bookSeat=false and owner remains user-1");
    }

    private static void testDifferentSeatsConcurrent()
            throws InterruptedException {
        SeatStore store = new SeatStore(Map.of("LAB_A", 2));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean firstResult = new AtomicBoolean();
        AtomicBoolean secondResult = new AtomicBoolean();

        Thread first = new Thread(() -> {
            ready.countDown();
            await(start);
            firstResult.set(store.bookSeat("LAB_A", 1, "user-1"));
        });
        Thread second = new Thread(() -> {
            ready.countDown();
            await(start);
            secondResult.set(store.bookSeat("LAB_A", 2, "user-2"));
        });

        first.start();
        second.start();
        ready.await();
        start.countDown();
        first.join();
        second.join();

        check("4. Different seats can be booked concurrently",
                firstResult.get()
                        && secondResult.get()
                        && "user-1".equals(store.getBookedUserId("LAB_A", 1))
                        && "user-2".equals(store.getBookedUserId("LAB_A", 2)),
                "both threads return true and both owners are recorded");
    }

    private static void testManyThreadsSameSeat()
            throws InterruptedException {
        SeatStore store = new SeatStore(Map.of("LAB_A", 1));
        int threadCount = 20;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successfulBookings = new AtomicInteger();

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            String userId = "user-" + i;
            threads[i] = new Thread(() -> {
                ready.countDown();
                await(start);
                if (store.bookSeat("LAB_A", 1, userId)) {
                    successfulBookings.incrementAndGet();
                }
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }
        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        check("5. Multiple threads same seat: exactly one success",
                successfulBookings.get() == 1
                        && store.isBooked("LAB_A", 1),
                "successfulBookings=" + successfulBookings.get()
                        + ", seatBooked=true");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        }
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
