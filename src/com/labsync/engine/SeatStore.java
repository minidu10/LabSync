package com.labsync.engine;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store for seats in multiple labs.
 *
 * <p>Seat identifiers are one-based: a lab configured with {@code 10} seats
 * has valid seat identifiers from {@code 1} through {@code 10}. Booking and
 * releasing different seats can proceed concurrently because synchronization
 * is performed on each individual seat, rather than on the whole store.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * SeatStore store = new SeatStore(Map.of("lab-a", 2, "lab-b", 3));
 *
 * boolean booked = store.bookSeat("lab-a", 1, "student-42"); // true
 * boolean attemptedAgain = store.bookSeat("lab-a", 1, "student-99"); // false
 *
 * String userId = store.getBookedUserId("lab-a", 1); // "student-42"
 * store.releaseSeat("lab-a", 1, "student-42"); // true
 * }</pre>
 */
public final class SeatStore {
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Seat>> labs =
            new ConcurrentHashMap<>();

    /**
     * Creates an empty store. Labs can be added with {@link #addLab(String, int)}.
     */
    public SeatStore() {
    }

    /**
     * Creates a store and adds each lab with the supplied number of seats.
     *
     * @param labSeatCounts lab IDs mapped to positive seat counts
     * @throws IllegalArgumentException if a lab ID or seat count is invalid
     */
    public SeatStore(Map<String, Integer> labSeatCounts) {
        Objects.requireNonNull(labSeatCounts, "labSeatCounts must not be null");
        labSeatCounts.forEach(this::addLab);
    }

    /**
     * Adds a lab whose seats are numbered from 1 through {@code seatCount}.
     *
     * @throws IllegalArgumentException if the ID is blank, the count is not
     *                                  positive, or the lab already exists
     */
    public void addLab(String labId, int seatCount) {
        validateLabId(labId);
        if (seatCount <= 0) {
            throw new IllegalArgumentException("seatCount must be greater than zero");
        }

        ConcurrentHashMap<Integer, Seat> seats = new ConcurrentHashMap<>();
        for (int seatId = 1; seatId <= seatCount; seatId++) {
            seats.put(seatId, new Seat());
        }
        if (labs.putIfAbsent(labId, seats) != null) {
            throw new IllegalArgumentException("Lab already exists: " + labId);
        }
    }

    /**
     * Attempts to book a seat for a user.
     *
     * @return {@code true} when the seat was booked, or {@code false} when it
     *         was already booked
     * @throws IllegalArgumentException for an unknown lab or seat
     */
    public boolean bookSeat(String labId, int seatId, String userId) {
        validateUserId(userId);
        Seat seat = findSeat(labId, seatId);
        synchronized (seat) {
            if (seat.booked) {
                return false;
            }
            seat.booked = true;
            seat.bookedUserId = userId;
            return true;
        }
    }

    /**
     * Releases a seat only when it is currently booked by {@code userId}.
     *
     * @return {@code true} when the booking was released, otherwise
     *         {@code false}
     * @throws IllegalArgumentException for an unknown lab or seat
     */
    public boolean releaseSeat(String labId, int seatId, String userId) {
        validateUserId(userId);
        Seat seat = findSeat(labId, seatId);
        synchronized (seat) {
            if (!seat.booked || !seat.bookedUserId.equals(userId)) {
                return false;
            }
            seat.booked = false;
            seat.bookedUserId = null;
            return true;
        }
    }

    /** Outcome of {@link #cancelSeat(String, int, String, boolean)}. */
    public enum CancelOutcome { CANCELLED, NOT_BOOKED, NOT_OWNER }

    /**
     * Cancels a booking. The check and the release happen inside one seat lock, so the
     * reported reason is always consistent with the seat state at that instant.
     *
     * @param userId   the user asking to cancel
     * @param override {@code true} lets the caller cancel another user's booking (ADMIN)
     * @return CANCELLED, NOT_BOOKED (seat was free) or NOT_OWNER (booked by someone else)
     * @throws IllegalArgumentException for an unknown lab or seat
     */
    public CancelOutcome cancelSeat(String labId, int seatId, String userId, boolean override) {
        validateUserId(userId);
        Seat seat = findSeat(labId, seatId);
        synchronized (seat) {
            if (!seat.booked) {
                return CancelOutcome.NOT_BOOKED;
            }
            if (!override && !seat.bookedUserId.equals(userId)) {
                return CancelOutcome.NOT_OWNER;
            }
            seat.booked = false;
            seat.bookedUserId = null;
            return CancelOutcome.CANCELLED;
        }
    }

    /**
     * Returns whether a seat is currently booked.
     *
     * @throws IllegalArgumentException for an unknown lab or seat
     */
    public boolean isBooked(String labId, int seatId) {
        Seat seat = findSeat(labId, seatId);
        synchronized (seat) {
            return seat.booked;
        }
    }

    /**
     * Returns the ID of the user who booked a seat, or {@code null} if it is
     * available.
     *
     * @throws IllegalArgumentException for an unknown lab or seat
     */
    public String getBookedUserId(String labId, int seatId) {
        Seat seat = findSeat(labId, seatId);
        synchronized (seat) {
            return seat.bookedUserId;
        }
    }

    /**
     * Returns the IDs of all configured labs.
     */
    public Set<String> getLabIds() {
        return Collections.unmodifiableSet(labs.keySet());
    }

    private Seat findSeat(String labId, int seatId) {
        validateLabId(labId);
        if (seatId <= 0) {
            throw new IllegalArgumentException("seatId must be greater than zero");
        }

        ConcurrentHashMap<Integer, Seat> seats = labs.get(labId);
        if (seats == null) {
            throw new IllegalArgumentException("Unknown lab: " + labId);
        }
        Seat seat = seats.get(seatId);
        if (seat == null) {
            throw new IllegalArgumentException(
                    "Unknown seat " + seatId + " in lab: " + labId);
        }
        return seat;
    }

    private static void validateLabId(String labId) {
        if (labId == null || labId.isBlank()) {
            throw new IllegalArgumentException("labId must not be blank");
        }
    }

    private static void validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
    }

    private static final class Seat {
        private boolean booked;
        private String bookedUserId;
    }
}
