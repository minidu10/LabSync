package com.labsync.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Adds receipt metadata to the result of a successful booking.
 *
 * <p>The generator is stateless and can safely be shared by booking tasks.
 * It does not perform booking or add security verification; those concerns
 * remain with {@link SeatStore} and the security component respectively.</p>
 */
public final class ReceiptGenerator {

    /**
     * Generates a unique ID and timestamp for a successful booking receipt.
     * Failure receipts are returned unchanged because only successful
     * bookings represent confirmed reservations.
     *
     * @param bookingReceipt result returned by a booking operation
     * @return the receipt with metadata, or the unchanged failure receipt
     */
    public BookingReceipt generate(BookingReceipt bookingReceipt) {
        Objects.requireNonNull(bookingReceipt, "bookingReceipt must not be null");
        if (!bookingReceipt.isSuccessful()) {
            return bookingReceipt;
        }

        return BookingReceipt.withReceiptDetails(
                bookingReceipt,
                UUID.randomUUID().toString(),
                Instant.now());
    }
}
