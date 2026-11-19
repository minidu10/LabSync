package com.labsync.engine;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.Callable;

public final class BookingTaskTest {
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        testCallableInterface();
        testAvailableSeat();
        testDuplicateBooking();
        testInvalidLab();
        testInvalidSeat();

        System.out.println();
        System.out.printf("RESULT: %d PASS, %d FAIL%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testCallableInterface() {
        boolean implementsCallable = Callable.class.isAssignableFrom(BookingTask.class);
        boolean callableTypeMatches = false;

        for (Type interfaceType : BookingTask.class.getGenericInterfaces()) {
            if (interfaceType instanceof ParameterizedType parameterizedType) {
                if (parameterizedType.getRawType() == Callable.class
                        && parameterizedType.getActualTypeArguments().length == 1
                        && parameterizedType.getActualTypeArguments()[0]
                                == BookingReceipt.class) {
                    callableTypeMatches = true;
                    break;
                }
            }
        }

        check("1. BookingTask implements Callable<BookingReceipt>",
                implementsCallable && callableTypeMatches,
                "BookingTask implements java.util.concurrent.Callable<BookingReceipt>");
    }

    private static void testAvailableSeat() throws Exception {
        SeatStore store = new SeatStore(Map.of("LAB_A", 2));
        BookingTask task = new BookingTask(store, "LAB_A", 1, "user-1");

        BookingReceipt receipt = task.call();

        check("2. Calling task books an available seat",
                store.isBooked("LAB_A", 1)
                        && "user-1".equals(store.getBookedUserId("LAB_A", 1)),
                "Seat LAB_A/S1 is booked by user-1");
        check("3. Task returns a successful BookingReceipt",
                receipt.isSuccessful()
                        && receipt.getStatus() == BookingReceipt.Status.SUCCESS
                        && "LAB_A".equals(receipt.getLabId())
                        && receipt.getSeatId() == 1
                        && "user-1".equals(receipt.getUserId()),
                "Status=SUCCESS with the correct lab, seat, and user");
    }

    private static void testDuplicateBooking() throws Exception {
        SeatStore store = new SeatStore(Map.of("LAB_A", 2));
        BookingReceipt first =
                new BookingTask(store, "LAB_A", 1, "user-1").call();
        BookingReceipt duplicate =
                new BookingTask(store, "LAB_A", 1, "user-2").call();

        check("4. Duplicate booking returns SEAT_ALREADY_TAKEN",
                first.isSuccessful()
                        && !duplicate.isSuccessful()
                        && duplicate.getStatus()
                                == BookingReceipt.Status.SEAT_ALREADY_TAKEN,
                "First task succeeds; second task status=SEAT_ALREADY_TAKEN");
    }

    private static void testInvalidLab() throws Exception {
        SeatStore store = new SeatStore(Map.of("LAB_A", 2));
        BookingReceipt receipt =
                new BookingTask(store, "MISSING_LAB", 1, "user-1").call();

        check("5. Invalid lab ID returns INVALID_REQUEST",
                !receipt.isSuccessful()
                        && receipt.getStatus()
                                == BookingReceipt.Status.INVALID_REQUEST,
                "Unknown lab returns status=INVALID_REQUEST");
    }

    private static void testInvalidSeat() throws Exception {
        SeatStore store = new SeatStore(Map.of("LAB_A", 2));
        BookingReceipt zeroSeat =
                new BookingTask(store, "LAB_A", 0, "user-1").call();
        BookingReceipt tooHigh =
                new BookingTask(store, "LAB_A", 3, "user-1").call();

        check("6. Invalid seat numbers return INVALID_REQUEST",
                zeroSeat.getStatus() == BookingReceipt.Status.INVALID_REQUEST
                        && tooHigh.getStatus()
                                == BookingReceipt.Status.INVALID_REQUEST,
                "Seat 0 and seat 3 both return INVALID_REQUEST");
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
