package com.labsync.engine;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fixed-size worker pool for asynchronous {@link BookingTask} execution.
 *
 * <p>Callers submit a booking request without executing it on an NIO selector
 * thread. The returned {@link Future} can then be observed for the
 * {@link BookingReceipt} result.</p>
 */
public final class BookingWorkerPool implements AutoCloseable {
    private final ExecutorService executor;

    /**
     * Creates a fixed-size pool.
     *
     * @param poolSize number of worker threads; must be greater than zero
     */
    public BookingWorkerPool(int poolSize) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be greater than zero");
        }

        executor = Executors.newFixedThreadPool(
                poolSize,
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "labsync-booking-worker-" + runnable.hashCode());
                    thread.setDaemon(false);
                    return thread;
                });
    }

    /**
     * Creates the default pool using two worker threads.
     */
    public BookingWorkerPool() {
        this(2);
    }

    /**
     * Submits a booking request asynchronously.
     *
     * @throws RejectedExecutionException if the pool is shut down
     */
    public Future<BookingReceipt> submitBooking(
            SeatStore seatStore,
            String labId,
            int seatId,
            String userId) {
        Objects.requireNonNull(seatStore, "seatStore must not be null");
        return submitTask(new BookingTask(seatStore, labId, seatId, userId));
    }

    /**
     * Submits a booking and delivers its result or failure through callbacks.
     *
     * <p>The callbacks run on a worker thread. Callers that update NIO state
     * should pass callbacks that enqueue work with
     * {@code NioGatewayServer.execute(...)} rather than touching sockets
     * directly.</p>
     */
    public Future<BookingReceipt> submitBooking(
            SeatStore seatStore,
            String labId,
            int seatId,
            String userId,
            Consumer<BookingReceipt> onSuccess,
            Consumer<Throwable> onFailure) {
        Objects.requireNonNull(seatStore, "seatStore must not be null");
        Objects.requireNonNull(onSuccess, "onSuccess must not be null");
        Objects.requireNonNull(onFailure, "onFailure must not be null");

        BookingTask task = new BookingTask(seatStore, labId, seatId, userId);
        try {
            return executor.submit(() -> {
                try {
                    BookingReceipt receipt = task.call();
                    onSuccess.accept(receipt);
                    return receipt;
                } catch (Throwable throwable) {
                    onFailure.accept(throwable);
                    if (throwable instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    if (throwable instanceof Error error) {
                        throw error;
                    }
                    throw new ExecutionException(throwable);
                }
            });
        } catch (RejectedExecutionException exception) {
            throw new RejectedExecutionException(
                    "Booking request was rejected because the worker pool is shut down",
                    exception);
        }
    }

    /**
     * Runs any seat operation (e.g. a cancellation) on the pool and delivers its result or
     * failure through callbacks, exactly like the callback form of {@code submitBooking}.
     *
     * @throws RejectedExecutionException if the pool is shut down
     */
    public <T> Future<T> submitAsync(
            Callable<T> work,
            Consumer<T> onSuccess,
            Consumer<Throwable> onFailure) {
        Objects.requireNonNull(work, "work must not be null");
        Objects.requireNonNull(onSuccess, "onSuccess must not be null");
        Objects.requireNonNull(onFailure, "onFailure must not be null");
        try {
            return executor.submit(() -> {
                try {
                    T result = work.call();
                    onSuccess.accept(result);
                    return result;
                } catch (Throwable throwable) {
                    onFailure.accept(throwable);
                    if (throwable instanceof Exception exception) {
                        throw exception;
                    }
                    throw (Error) throwable;
                }
            });
        } catch (RejectedExecutionException exception) {
            throw new RejectedExecutionException(
                    "Request was rejected because the worker pool is shut down",
                    exception);
        }
    }

    /**
     * Submits an existing booking task.
     *
     * @throws RejectedExecutionException if the pool is shut down
     */
    public Future<BookingReceipt> submitTask(BookingTask task) {
        Objects.requireNonNull(task, "task must not be null");
        try {
            return executor.submit(task);
        } catch (RejectedExecutionException exception) {
            throw new RejectedExecutionException(
                    "Booking request was rejected because the worker pool is shut down",
                    exception);
        }
    }

    /**
     * Blocks until the booking task completes and returns its receipt.
     */
    public BookingReceipt getReceipt(Future<BookingReceipt> future)
            throws InterruptedException, ExecutionException, CancellationException {
        Objects.requireNonNull(future, "future must not be null");
        return future.get();
    }

    /**
     * Waits for a receipt for at most the supplied timeout.
     */
    public BookingReceipt getReceipt(
            Future<BookingReceipt> future,
            long timeout,
            TimeUnit unit)
            throws InterruptedException,
            ExecutionException,
            TimeoutException,
            CancellationException {
        Objects.requireNonNull(future, "future must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        return future.get(timeout, unit);
    }

    /**
     * Stops accepting new tasks and allows previously submitted tasks to finish.
     */
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * Attempts to stop running tasks and cancels tasks that have not started.
     */
    public List<Runnable> shutdownNow() {
        return executor.shutdownNow();
    }

    /**
     * Waits for existing tasks to complete after shutdown.
     *
     * @return {@code true} if all tasks terminated
     */
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        Objects.requireNonNull(unit, "unit must not be null");
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        return executor.awaitTermination(timeout, unit);
    }

    public boolean isShutdown() {
        return executor.isShutdown();
    }

    public boolean isTerminated() {
        return executor.isTerminated();
    }

    /**
     * AutoCloseable implementation; shuts down gracefully.
     */
    @Override
    public void close() {
        shutdown();
    }
}
