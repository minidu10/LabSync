package com.labsync.engine;

import java.time.Instant;
import java.util.UUID;

public final class ReceiptGeneratorTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testReceiptId();
        testTimestamp();
        testUniqueReceiptIds();
        testFailureReceipt();
        testMetadataAttachment();

        System.out.println();
        System.out.printf("RESULT: %d PASS, %d FAIL%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testReceiptId() {
        ReceiptGenerator generator = new ReceiptGenerator();
        BookingReceipt generated = generator.generate(
                BookingReceipt.success("LAB_A", 1, "user-1"));

        boolean validUuid = false;
        if (generated.getReceiptId() != null) {
            try {
                UUID.fromString(generated.getReceiptId());
                validUuid = true;
            } catch (IllegalArgumentException ignored) {
                validUuid = false;
            }
        }

        check("1. Successful booking receives a valid receipt ID",
                validUuid,
                "receiptId is non-null and is a valid UUID");
    }

    private static void testTimestamp() {
        ReceiptGenerator generator = new ReceiptGenerator();
        Instant before = Instant.now();
        BookingReceipt generated = generator.generate(
                BookingReceipt.success("LAB_A", 2, "user-2"));
        Instant after = Instant.now();

        Instant timestamp = generated.getTimestamp();
        check("2. Successful receipt contains a valid timestamp",
                timestamp != null
                        && !timestamp.isBefore(before)
                        && !timestamp.isAfter(after),
                "timestamp is present and falls between before and after");
    }

    private static void testUniqueReceiptIds() {
        ReceiptGenerator generator = new ReceiptGenerator();
        BookingReceipt first = generator.generate(
                BookingReceipt.success("LAB_A", 1, "user-1"));
        BookingReceipt second = generator.generate(
                BookingReceipt.success("LAB_A", 2, "user-2"));

        check("3. Two successful bookings receive unique receipt IDs",
                first.getReceiptId() != null
                        && second.getReceiptId() != null
                        && !first.getReceiptId().equals(second.getReceiptId()),
                "first and second receiptId values are different");
    }

    private static void testFailureReceipt() {
        ReceiptGenerator generator = new ReceiptGenerator();
        BookingReceipt failure = BookingReceipt.failure(
                BookingReceipt.Status.SEAT_ALREADY_TAKEN,
                "LAB_A",
                1,
                "user-2",
                "Seat is already booked");

        BookingReceipt result = generator.generate(failure);

        check("4. Failed booking is not converted into success receipt",
                result == failure
                        && !result.isSuccessful()
                        && result.getStatus()
                                == BookingReceipt.Status.SEAT_ALREADY_TAKEN
                        && result.getReceiptId() == null
                        && result.getTimestamp() == null,
                "same failure receipt, SEAT_ALREADY_TAKEN, no receipt metadata");
    }

    private static void testMetadataAttachment() {
        ReceiptGenerator generator = new ReceiptGenerator();
        BookingReceipt generated = generator.generate(
                BookingReceipt.success("LAB_B", 7, "user-7"));

        check("5. Receipt metadata preserves booking details",
                generated.isSuccessful()
                        && "LAB_B".equals(generated.getLabId())
                        && generated.getSeatId() == 7
                        && "user-7".equals(generated.getUserId())
                        && generated.getStatus() == BookingReceipt.Status.SUCCESS
                        && generated.getReceiptId() != null
                        && generated.getTimestamp() != null,
                "status, lab, seat, user, receiptId, and timestamp are all present");
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
