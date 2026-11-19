package com.labsync.engine;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable result of a seat-booking request.
 */
public final class BookingReceipt {
    public enum Status {
        SUCCESS,
        SEAT_ALREADY_TAKEN,
        INVALID_REQUEST
    }

    private final Status status;
    private final String labId;
    private final int seatId;
    private final String userId;
    private final String message;
    private final String receiptId;
    private final Instant timestamp;

    private BookingReceipt(Status status, String labId, int seatId, String userId,
            String message, String receiptId, Instant timestamp) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.labId = labId;
        this.seatId = seatId;
        this.userId = userId;
        this.message = Objects.requireNonNull(message, "message must not be null");
        this.receiptId = receiptId;
        this.timestamp = timestamp;
    }

    public static BookingReceipt success(String labId, int seatId, String userId) {
        return new BookingReceipt(Status.SUCCESS, labId, seatId, userId,
                "Seat booked successfully", null, null);
    }

    public static BookingReceipt failure(Status status, String labId, int seatId,
            String userId, String message) {
        if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("A failure receipt cannot have SUCCESS status");
        }
        return new BookingReceipt(status, labId, seatId, userId, message, null, null);
    }

    static BookingReceipt withReceiptDetails(BookingReceipt bookingReceipt,
            String receiptId, Instant timestamp) {
        return new BookingReceipt(
                bookingReceipt.status,
                bookingReceipt.labId,
                bookingReceipt.seatId,
                bookingReceipt.userId,
                bookingReceipt.message,
                receiptId,
                timestamp);
    }

    public Status getStatus() {
        return status;
    }

    public String getLabId() {
        return labId;
    }

    public int getSeatId() {
        return seatId;
    }

    public String getUserId() {
        return userId;
    }

    public String getMessage() {
        return message;
    }

    public boolean isSuccessful() {
        return status == Status.SUCCESS;
    }

    public String getReceiptId() {
        return receiptId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
