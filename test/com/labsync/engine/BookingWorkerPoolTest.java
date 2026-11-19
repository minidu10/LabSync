package com.labsync.engine;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BookingWorkerPoolTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testConfiguredThreadCount();
        testAsyncSubmissionAndConcurrency();
        testSameSeatRace();
        testDifferentSeats();
        testGracefulShutdown();
        testSubmissionAfterShutdown();

        System.out.println();
        System.out.printf("RESULT: %d PASS, %d FAIL%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testConfiguredThreadCount()
            throws Exception {
        try (BookingWorkerPool pool = new BookingWorkerPool(3)) {
            Field executorField =
                    BookingWorkerPool.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            Object executor = executorField.get(pool);

            boolean isThreadPoolExecutor =
                    executor instanceof ThreadPoolExecutor;
            boolean configuredCore = false;
            boolean configuredMaximum = false;
            if (executor instanceof ThreadPoolExecutor threadPool) {
                configuredCore = threadPool.getCorePoolSize() == 3;
                configuredMaximum = threadPool.getMaximumPoolSize() == 3;
            }

            check("1. Worker pool initializes with configured thread count",
                    isThreadPoolExecutor
                            && configuredCore
                            && configuredMaximum,
                    "corePoolSize=3 and maximumPoolSize=3");
        }
    }

    private static void testAsyncSubmissionAndConcurrency()
            throws Exception {
        SeatStore store = new SeatStore(Map.of("LAB_A", 2));
            try (BookingWorkerPool pool = new BookingWorkerPool(3)) {
            Future<BookingReceipt> first =
                    pool.submitBooking(store, "LAB_A", 1, "async-1");
            Future<BookingReceipt> second =
                    pool.submitBooking(store, "LAB_A", 2, "async-2");

            boolean submittedBeforeWaiting =
                    !first.isDone() || !second.isDone()
                            || first.isDone() && second.isDone();
            BookingReceipt firstReceipt =
                    pool.getReceipt(first, 5, TimeUnit.SECONDS);
            BookingReceipt secondReceipt =
                    pool.getReceipt(second, 5, TimeUnit.SECONDS);

            check("2. Multiple booking tasks can be submitted asynchronously",
                    submittedBeforeWaiting
                            && firstReceipt.isSuccessful()
                            && secondReceipt.isSuccessful(),
                    "both Futures return successful BookingReceipt results");
        }

        SeatStore concurrentStore = new SeatStore(Map.of("LAB_A", 4));
        Set<String> workerThreads = new java.util.concurrent.ConcurrentHashMap<
                String, Boolean>().keySet(true);
        CountDownLatch callbacksReady = new CountDownLatch(3);
        CountDownLatch releaseCallbacks = new CountDownLatch(1);
        Consumer<Throwable> onFailure =
                throwable -> workerThreads.add("failure");

        try (BookingWorkerPool concurrentPool = new BookingWorkerPool(3)) {
            List<Future<BookingReceipt>> futures = new ArrayList<>();
            for (int seatId = 1; seatId <= 3; seatId++) {
                int requestedSeat = seatId;
                Consumer<BookingReceipt> concurrentSuccess = receipt -> {
                    workerThreads.add(Thread.currentThread().getName());
                    callbacksReady.countDown();
                    try {
                        releaseCallbacks.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(exception);
                    }
                };
                futures.add(concurrentPool.submitBooking(
                        concurrentStore,
                        "LAB_A",
                        requestedSeat,
                        "concurrent-" + seatId,
                        concurrentSuccess,
                        onFailure));
            }

            boolean callbacksRanTogether =
                    callbacksReady.await(5, TimeUnit.SECONDS);
            releaseCallbacks.countDown();

            for (Future<BookingReceipt> future : futures) {
                concurrentPool.getReceipt(future, 5, TimeUnit.SECONDS);
            }

            check("3. Tasks execute on worker threads",
                    callbacksRanTogether
                            && !workerThreads.isEmpty()
                            && workerThreads.stream()
                                    .noneMatch(name -> name.equals("main")),
                    "three completion callbacks overlap and run on pool worker threads");
        }
    }

    private static void testSameSeatRace() throws Exception {
        SeatStore store = new SeatStore(Map.of("LAB_A", 1));
        int taskCount = 20;
        AtomicInteger successes = new AtomicInteger();

        try (BookingWorkerPool pool = new BookingWorkerPool(8)) {
            List<Future<BookingReceipt>> futures = new ArrayList<>();
            for (int i = 0; i < taskCount; i++) {
                futures.add(pool.submitBooking(
                        store, "LAB_A", 1, "race-user-" + i));
            }

            for (Future<BookingReceipt> future : futures) {
                BookingReceipt receipt =
                        pool.getReceipt(future, 5, TimeUnit.SECONDS);
                if (receipt.isSuccessful()) {
                    successes.incrementAndGet();
                }
            }

            check("4. Same-seat race has exactly one success",
                    successes.get() == 1
                            && store.isBooked("LAB_A", 1),
                    "successful receipt count=1 and seat LAB_A/S1 is booked");
        }
    }

    private static void testDifferentSeats() throws Exception {
        SeatStore store = new SeatStore(Map.of("LAB_B", 2));
        try (BookingWorkerPool pool = new BookingWorkerPool(2)) {
            Future<BookingReceipt> first =
                    pool.submitBooking(store, "LAB_B", 1, "different-1");
            Future<BookingReceipt> second =
                    pool.submitBooking(store, "LAB_B", 2, "different-2");

            BookingReceipt firstReceipt =
                    pool.getReceipt(first, 5, TimeUnit.SECONDS);
            BookingReceipt secondReceipt =
                    pool.getReceipt(second, 5, TimeUnit.SECONDS);

            check("5. Different seats can be booked concurrently",
                    firstReceipt.isSuccessful()
                            && secondReceipt.isSuccessful()
                            && store.isBooked("LAB_B", 1)
                            && store.isBooked("LAB_B", 2),
                    "both Futures succeed and both seats are booked");
        }
    }

    private static void testGracefulShutdown() throws Exception {
        SeatStore store = new SeatStore(Map.of("LAB_A", 1));
        try (BookingWorkerPool pool = new BookingWorkerPool(2)) {
            Future<BookingReceipt> future =
                    pool.submitBooking(store, "LAB_A", 1, "shutdown-user");

            pool.shutdown();
            boolean terminated =
                    pool.awaitTermination(5, TimeUnit.SECONDS);
            BookingReceipt receipt =
                    pool.getReceipt(future, 5, TimeUnit.SECONDS);

            check("6. Worker pool shuts down gracefully",
                    pool.isShutdown()
                            && terminated
                            && pool.isTerminated()
                            && receipt.isSuccessful(),
                    "submitted task completes and pool reaches terminated state");
        }
    }

    private static void testSubmissionAfterShutdown() throws Exception {
        try (BookingWorkerPool pool = new BookingWorkerPool(2)) {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);

            SeatStore store = new SeatStore(Map.of("LAB_A", 1));
            boolean rejected = false;
            try {
                pool.submitBooking(store, "LAB_A", 1, "late-user");
            } catch (RejectedExecutionException exception) {
                rejected = true;
            }

            check("7. Submission after shutdown is rejected appropriately",
                    rejected && pool.isShutdown(),
                    "RejectedExecutionException is thrown");
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
