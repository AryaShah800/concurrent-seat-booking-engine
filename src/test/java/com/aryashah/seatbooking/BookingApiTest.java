package com.aryashah.seatbooking;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryashah.seatbooking.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Exercises the REST contract end to end over HTTP. */
class BookingApiTest extends IntegrationTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    void fullFlow_createShowHoldConfirmReplayAndFetch() {
        ResponseEntity<Map> show = http.postForEntity("/api/shows",
                Map.of("name", "Concert", "startsAt", "2026-12-31T18:00:00Z", "rows", 2, "seatsPerRow", 3), Map.class);
        assertThat(show.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object showId = show.getBody().get("id");

        ResponseEntity<List> seats = http.getForEntity("/api/shows/" + showId + "/seats", List.class);
        assertThat(seats.getBody()).hasSize(6);
        Map<?, ?> firstSeat = (Map<?, ?>) seats.getBody().get(0);
        assertThat(firstSeat.get("label")).isEqualTo("A1");
        assertThat(firstSeat.get("status")).isEqualTo("AVAILABLE");

        ResponseEntity<Map> hold = http.postForEntity("/api/holds",
                Map.of("seatId", firstSeat.get("id"), "customerId", "alice"), Map.class);
        assertThat(hold.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(hold.getBody()).containsKeys("holdId", "expiresAt");

        String key = "api-" + UUID.randomUUID();
        ResponseEntity<Map> booking = confirm(key, hold.getBody().get("holdId"), "alice");
        assertThat(booking.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(booking.getHeaders().getLocation()).isNotNull();

        ResponseEntity<Map> replay = confirm(key, hold.getBody().get("holdId"), "alice");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("bookingId")).isEqualTo(booking.getBody().get("bookingId"));

        ResponseEntity<Map> fetched = http.getForEntity("/api/bookings/" + booking.getBody().get("bookingId"), Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<List> after = http.getForEntity("/api/shows/" + showId + "/seats", List.class);
        assertThat(((Map<?, ?>) after.getBody().get(0)).get("status")).isEqualTo("BOOKED");
    }

    @Test
    void secondHoldOnSameSeat_isAConflictProblem() {
        Long seatId = newSeat().getId();
        http.postForEntity("/api/holds", Map.of("seatId", seatId, "customerId", "alice"), Map.class);

        ResponseEntity<Map> second = http.postForEntity("/api/holds", Map.of("seatId", seatId, "customerId", "bob"), Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code")).isEqualTo("SEAT_UNAVAILABLE");
    }

    @Test
    void releaseEndpoint_freesTheSeat() {
        Long seatId = newSeat().getId();
        ResponseEntity<Map> hold = http.postForEntity("/api/holds", Map.of("seatId", seatId, "customerId", "alice"), Map.class);

        ResponseEntity<Void> released = http.exchange("/api/holds/" + hold.getBody().get("holdId") + "?customerId=alice",
                HttpMethod.DELETE, null, Void.class);

        assertThat(released.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.postForEntity("/api/holds", Map.of("seatId", seatId, "customerId", "bob"), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void confirmWithoutIdempotencyKey_isBadRequest() {
        ResponseEntity<Map> response = http.postForEntity("/api/bookings",
                Map.of("holdId", UUID.randomUUID(), "customerId", "alice"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tooShortIdempotencyKey_isBadRequest() {
        ResponseEntity<Map> response = confirm("short", UUID.randomUUID(), "alice");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidBodies_areBadRequests() {
        assertThat(http.postForEntity("/api/holds", Map.of("customerId", "alice"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/shows",
                Map.of("name", "x", "startsAt", "2026-12-31T18:00:00Z", "rows", 27, "seatsPerRow", 1), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownResources_areNotFoundProblems() {
        ResponseEntity<Map> seats = http.getForEntity("/api/shows/999999999/seats", Map.class);
        assertThat(seats.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(seats.getBody().get("code")).isEqualTo("NOT_FOUND");

        assertThat(http.getForEntity("/api/bookings/" + UUID.randomUUID(), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void openApiDocsAndHealth_areServed() {
        assertThat(http.getForEntity("/v3/api-docs", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map> confirm(String key, Object holdId, String customerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return http.postForEntity("/api/bookings",
                new HttpEntity<>(Map.of("holdId", holdId, "customerId", customerId), headers), Map.class);
    }
}
