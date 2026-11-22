package com.labsync.udp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure JDK unit and integration tests for Member 3's UDP status feed.
 *
 * <p>Validates:
 * <ul>
 *   <li>Frame parsing and serialization to wire format</li>
 *   <li>Out-of-order packet mitigation (Zombie State protection)</li>
 *   <li>Duplicate datagram rejection</li>
 *   <li>Asynchronous listener dispatch</li>
 * </ul>
 */
public final class UdpStatusTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testFrameParsingAndSerialization();
        testMalformedFramesHandling();
        testMonotonicSequenceAcceptance();
        testOutOfOrderRejectionSafeguard();
        testDuplicateReplayRejection();
        testAsynchronousObserverDispatch();

        System.out.println();
        System.out.printf("RESULT: %d PASS, %d FAIL%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testFrameParsingAndSerialization() {
        String raw = "SEQ:42|LAB_A|S05|BOOKED|student101";
        SeatStatusUpdate update = SeatStatusUpdate.parse(raw);

        boolean fieldsMatch = update.sequenceNumber() == 42
                && "LAB_A".equals(update.labId())
                && "S05".equals(update.seatId())
                && "BOOKED".equals(update.status())
                && "student101".equals(update.userId());

        String serialized = update.toFrame();
        boolean roundTrip = raw.equals(serialized);

        check("1. UDP wire frame parses and serializes correctly",
                fieldsMatch && roundTrip,
                "fields match and round-trip serialization preserves exact frame");
    }

    private static void testMalformedFramesHandling() {
        String[] badFrames = {
                null,
                "",
                "   ",
                "INVALID_FRAME",
                "SEQ:abc|LAB_A|S01|BOOKED|user1",
                "SEQ:1|LAB_A|S01|BOOKED", // missing token
                "SEQ:1||S01|BOOKED|user1"  // empty labId
        };

        int caught = 0;
        for (String bad : badFrames) {
            try {
                SeatStatusUpdate.parse(bad);
            } catch (IllegalArgumentException e) {
                caught++;
            }
        }

        check("2. Malformed or truncated UDP frames are safely rejected",
                caught == badFrames.length,
                "All " + badFrames.length + " malformed frames threw IllegalArgumentException");
    }

    private static void testMonotonicSequenceAcceptance() {
        UdpStatusListener listener = new UdpStatusListener(6000);

        listener.processIncomingFrame("SEQ:1|LAB_A|S01|BOOKED|u1");
        listener.processIncomingFrame("SEQ:2|LAB_A|S02|BOOKED|u2");
        listener.processIncomingFrame("SEQ:5|LAB_A|S03|BOOKED|u3"); // Allowed sequence jump (packet loss)

        boolean valid = listener.getLastSeenSeq() == 5
                && listener.getAcceptedPackets() == 3
                && listener.getDroppedPackets() == 0;

        check("3. Monotonically increasing sequence frames are accepted",
                valid,
                "lastSeenSeq=5, acceptedPackets=3, droppedPackets=0");
    }

    private static void testOutOfOrderRejectionSafeguard() {
        UdpStatusListener listener = new UdpStatusListener(6000);

        // Sequence reaches 7
        listener.processIncomingFrame("SEQ:7|LAB_A|S05|BOOKED|alice");

        // Delayed out-of-order packet arriving late over lossy network
        listener.processIncomingFrame("SEQ:3|LAB_A|S05|FREE|SYSTEM");

        // Subsequent valid in-order packet
        listener.processIncomingFrame("SEQ:8|LAB_A|S05|RESERVED|bob");

        boolean safeguarded = listener.getLastSeenSeq() == 8
                && listener.getAcceptedPackets() == 2
                && listener.getDroppedPackets() == 1;

        check("4. Out-of-order datagram (SEQ:3 after SEQ:7) is dropped to prevent zombie state",
                safeguarded,
                "accepted=2, dropped=1, lastSeenSeq=8");
    }

    private static void testDuplicateReplayRejection() {
        UdpStatusListener listener = new UdpStatusListener(6000);

        listener.processIncomingFrame("SEQ:10|LAB_B|S01|BOOKED|charlie");
        // Duplicate packet with exact same sequence number (network reflection/replay)
        listener.processIncomingFrame("SEQ:10|LAB_B|S01|BOOKED|charlie");

        boolean rejected = listener.getLastSeenSeq() == 10
                && listener.getAcceptedPackets() == 1
                && listener.getDroppedPackets() == 1;

        check("5. Duplicate datagram replay is dropped (incomingSeq <= lastSeenSeq)",
                rejected,
                "accepted=1, dropped=1, lastSeenSeq=10");
    }

    private static void testAsynchronousObserverDispatch() {
        UdpStatusListener listener = new UdpStatusListener(6000);
        List<SeatStatusUpdate> receivedUpdates = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        listener.addStatusListener(update -> {
            receivedUpdates.add(update);
            latch.countDown();
        });

        listener.processIncomingFrame("SEQ:1|LAB_A|S10|BOOKED|tester");

        boolean notified = false;
        try {
            notified = latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean matched = !receivedUpdates.isEmpty()
                && receivedUpdates.get(0).sequenceNumber() == 1
                && "S10".equals(receivedUpdates.get(0).seatId());

        check("6. Accepted updates are dispatched asynchronously to observers",
                notified && matched,
                "callback received update with seatId=S10");
    }

    private static void check(String testName, boolean condition, String expected) {
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

