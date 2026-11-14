package com.aryashah.seatbooking;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryashah.seatbooking.domain.HoldStatus;
import com.aryashah.seatbooking.domain.Seat;
import com.aryashah.seatbooking.domain.SeatStatus;
import com.aryashah.seatbooking.repository.BookingRepository;
import com.aryashah.seatbooking.repository.HoldRepository;
import com.aryashah.seatbooking.service.ApiException;
import com.aryashah.seatbooking.service.BookingService;
import com.aryashah.seatbooking.service.BookingService.BookingResult;
import com.aryashah.seatbooking.domain.Hold;
import com.aryashah.seatbooking.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * N threads race for the same seat against a real PostgreSQL. Every racer is released at the same
 * instant by a start gate, and the test asserts that exactly one wins.
 */
class ConcurrentBookingTest extends IntegrationTest {

    private static final int THREADS = 50;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private HoldRepository holdRepository;

    @Autowired
    private TestRestTemplate http;

    @RepeatedTest(5)
    void nThreadsRacingToHoldTheSameSeat_exactlyOneWins() throws Exception {
        Seat seat = newSeat();
        AtomicInteger conflicts = new AtomicInteger();

        List<Hold> winners = race(THREADS, i -> {
            try {
                return bookingService.placeHold(seat.getId(), "customer-" + i);
            } catch (ApiException e) {
                assertThat(e.getCode()).isEqualTo("SEAT_UNAVAILABLE");
                conflicts.incrementAndGet();
                return null;
            }
        });

        assertThat(winners).hasSize(1);
        assertThat(conflicts.get()).isEqualTo(THREADS - 1);
        Seat reloaded = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(reloaded.getHoldId()).isEqualTo(winners.get(0).getId());
        assertThat(holdRepository.findAll().stream()
                .filter(h -> h.getSeatId().equals(seat.getId()) && h.getStatus() == HoldStatus.ACTIVE))
                .hasSize(1);
    }

    @Test
    void concurrentConfirmsWithTheSameIdempotencyKey_createOneBookingAndReplayTheRest() throws Exception {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "alice");
        String key = "key-" + UUID.randomUUID();

        List<BookingResult> results = race(20, i -> bookingService.confirm(key, hold.getId(), "alice"));

        assertThat(results).hasSize(20);
        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(results).extracting(r -> r.booking().getId()).containsOnly(results.get(0).booking().getId());
        assertThat(bookingRepository.countBySeatId(seat.getId())).isEqualTo(1);
    }

    @Test
    void concurrentConfirmsWithDifferentKeys_onlyOneBooks() throws Exception {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "alice");
        AtomicInteger rejected = new AtomicInteger();

        List<BookingResult> results = race(20, i -> {
            try {
                return bookingService.confirm("key-" + UUID.randomUUID(), hold.getId(), "alice");
            } catch (ApiException e) {
                assertThat(e.getCode()).isEqualTo("HOLD_NOT_ACTIVE");
                rejected.incrementAndGet();
                return null;
            }
        });

        assertThat(results).hasSize(1);
        assertThat(rejected.get()).isEqualTo(19);
        assertThat(bookingRepository.countBySeatId(seat.getId())).isEqualTo(1);
    }

    @Test
    void nClientsRacingOverHttpToHoldAndBookOneSeat_produceExactlyOneBooking() throws Exception {
        Seat seat = newSeat();
        Map<Integer, Integer> statusCounts = new ConcurrentHashMap<>();

        List<String> booked = race(THREADS, i -> {
            String customer = "http-customer-" + i;
            ResponseEntity<Map> hold = http.postForEntity("/api/holds",
                    Map.of("seatId", seat.getId(), "customerId", customer), Map.class);
            statusCounts.merge(hold.getStatusCode().value(), 1, Integer::sum);
            if (hold.getStatusCode() != HttpStatus.CREATED) {
                return null;
            }
            HttpHeaders headers = new HttpHeaders();
            headers.set("Idempotency-Key", "http-" + UUID.randomUUID());
            ResponseEntity<Map> booking = http.postForEntity("/api/bookings",
                    new HttpEntity<>(Map.of("holdId", hold.getBody().get("holdId"), "customerId", customer), headers),
                    Map.class);
            return booking.getStatusCode() == HttpStatus.CREATED ? customer : null;
        });

        assertThat(booked).hasSize(1);
        assertThat(statusCounts).containsEntry(201, 1).containsEntry(409, THREADS - 1);
        assertThat(bookingRepository.countBySeatId(seat.getId())).isEqualTo(1);
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.BOOKED);
    }

    /** Runs {@code task} on n threads that all start at once. Returns the non-null results. */
    private <T> List<T> race(int n, IndexedTask<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                Callable<T> callable = () -> {
                    ready.countDown();
                    go.await();
                    return task.run(index);
                };
                futures.add(pool.submit(callable));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                T result = future.get(30, TimeUnit.SECONDS);
                if (result != null) {
                    results.add(result);
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IndexedTask<T> {
        T run(int index) throws Exception;
    }
}
