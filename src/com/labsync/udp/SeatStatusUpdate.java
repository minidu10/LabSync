package com.labsync.udp;

import java.util.Objects;

/**
 * Immutable representation of a live seat-status update frame transmitted over UDP Port 6000.
 *
 * <p><b>Shared Message Protocol Specification (Port 6000):</b>
 * <pre>
 *   SEQ:&lt;n&gt;|&lt;labId&gt;|&lt;seatId&gt;|&lt;STATUS&gt;|&lt;userId&gt;
 * </pre>
 * Example: {@code SEQ:1|LAB_A|S01|BOOKED|student101}
 *
 * <p>Lecture Concepts & Assignment Alignment:
 * <ul>
 *   <li><b>Monotonic Sequence Counter (SEQ:&lt;n&gt;):</b> Embedded sequence identifier enabling
 *       client-side detection and mitigation of out-of-order UDP datagram delivery.</li>
 *   <li><b>Connectionless Frame Parsing:</b> Lightweight text-based serialization suited for
 *       low-latency UDP datagram transport across local network broadcast domains.</li>
 * </ul>
 */
public record SeatStatusUpdate(
        long sequenceNumber,
        String labId,
        String seatId,
        String status,
        String userId,
        long timestamp
) {

    public SeatStatusUpdate {
        Objects.requireNonNull(labId, "labId cannot be null");
        Objects.requireNonNull(seatId, "seatId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        if (userId == null || userId.isBlank()) {
            userId = "SYSTEM";
        }
        if (timestamp <= 0) {
            timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Convenience constructor setting current system timestamp.
     *
     * @param sequenceNumber Monotonically increasing sequence number
     * @param labId          Laboratory identifier (e.g. "LAB_A")
     * @param seatId         Seat identifier (e.g. "S01")
     * @param status         Seat status (e.g. "FREE", "BOOKED", "RESERVED")
     * @param userId         User claiming the seat or "SYSTEM"
     */
    public SeatStatusUpdate(long sequenceNumber, String labId, String seatId, String status, String userId) {
        this(sequenceNumber, labId, seatId, status, userId, System.currentTimeMillis());
    }

    /**
     * Parses a raw UDP broadcast payload frame into a {@link SeatStatusUpdate}.
     *
     * <p>Expected frame format: {@code SEQ:<n>|<labId>|<seatId>|<STATUS>|<userId>}
     *
     * @param frame Raw UTF-8 string payload received from a UDP datagram
     * @return Validated {@link SeatStatusUpdate} object
     * @throws IllegalArgumentException if the frame is malformed or missing required tokens
     */
    public static SeatStatusUpdate parse(String frame) {
        if (frame == null || frame.isBlank()) {
            throw new IllegalArgumentException("UDP broadcast frame cannot be null or empty");
        }

        String trimmed = frame.trim();
        String[] tokens = trimmed.split("\\|", -1);
        if (tokens.length < 5) {
            throw new IllegalArgumentException(
                    "Invalid UDP frame format (expected 5 tokens separated by '|'): " + trimmed);
        }

        String seqPart = tokens[0].trim();
        if (!seqPart.startsWith("SEQ:")) {
            throw new IllegalArgumentException("UDP frame must start with 'SEQ:': " + trimmed);
        }

        long sequenceNumber;
        try {
            sequenceNumber = Long.parseLong(seqPart.substring(4).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid sequence number in UDP frame: " + seqPart, e);
        }

        String labId = tokens[1].trim();
        String seatId = tokens[2].trim();
        String status = tokens[3].trim().toUpperCase();
        String userId = tokens[4].trim();

        if (labId.isEmpty() || seatId.isEmpty() || status.isEmpty()) {
            throw new IllegalArgumentException("Empty field detected in UDP frame: " + trimmed);
        }

        return new SeatStatusUpdate(sequenceNumber, labId, seatId, status, userId, System.currentTimeMillis());
    }

    /**
     * Formats this update into the canonical UTF-8 wire protocol frame string.
     *
     * @return String formatted as {@code SEQ:<n>|<labId>|<seatId>|<STATUS>|<userId>}
     */
    public String toFrame() {
        return String.format("SEQ:%d|%s|%s|%s|%s",
                sequenceNumber, labId, seatId, status, userId != null ? userId : "SYSTEM");
    }

    @Override
    public String toString() {
        return String.format("SeatStatusUpdate[seq=%d, lab=%s, seat=%s, status=%s, user=%s]",
                sequenceNumber, labId, seatId, status, userId);
    }
}

