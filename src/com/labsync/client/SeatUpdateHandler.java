package com.labsync.client;

/**
 * Integration point for Member 3's UDP feed (port 6000).
 *
 * UdpStatusListener parses  SEQ:<n>|<labId>|<seatId>|<STATUS>|<userId>,
 * drops stale packets (incomingSeq <= lastSeenSeq), and then calls
 * onSeatUpdate(...) for each accepted packet. SeatGridPanel implements this.
 *
 * It may be called from ANY thread (e.g. the UDP receive thread);
 * the implementation is responsible for moving work onto the Swing thread.
 */
@FunctionalInterface
public interface SeatUpdateHandler {
    void onSeatUpdate(String labId, String seatId, String status, String userId);
}
