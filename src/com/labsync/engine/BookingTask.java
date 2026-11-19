package com.labsync.engine;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * A single booking request that can be run by an executor.
 *
 * <p>{@link #call()} delegates the atomic booking operation to
 * {@link SeatStore#bookSeat(String, int, String)}. This class intentionally
 * does not add another synchronization layer.</p>
 */
public final class BookingTask implements Callable<BookingReceipt> {
    private static final ReceiptGenerator RECEIPT_GENERATOR = new ReceiptGenerator();

    private final SeatStore seatStore;
    private final String labId;
    private final int seatId;
    private final String userId;

    public BookingTask(SeatStore seatStore, String labId, int seatId, String userId) {
        this.seatStore = Objects.requireNonNull(seatStore, "seatStore must not be null");
        this.labId = labId;
        this.seatId = seatId;
        this.userId = userId;
    }

    /**
     * Attempts the booking and returns a receipt instead of exposing an
     * expected booking conflict as an exception.
     */
    @Override
    public BookingReceipt call() {
        try {
            boolean booked = seatStore.bookSeat(labId, seatId, userId);
            if (booked) {
                return RECEIPT_GENERATOR.generate(
                        BookingReceipt.success(labId, seatId, userId));
            }
            return BookingReceipt.failure(
                    BookingReceipt.Status.SEAT_ALREADY_TAKEN,
                    labId,
                    seatId,
                    userId,
                    "Seat is already booked");
        } catch (IllegalArgumentException exception) {
            return BookingReceipt.failure(
                    BookingReceipt.Status.INVALID_REQUEST,
                    labId,
                    seatId,
                    userId,
                    exception.getMessage());
        }
    }
}
