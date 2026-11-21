package com.aryashah.seatbooking;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryashah.seatbooking.domain.Hold;
import com.aryashah.seatbooking.domain.Seat;
import com.aryashah.seatbooking.service.BookingService;
import com.aryashah.seatbooking.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class MetricsEndpointTest extends IntegrationTest {

    @Autowired
    BookingService bookingService;
    @Autowired
    TestRestTemplate rest;

    @Test
    void prometheusEndpointExposesBookingMetrics() {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "metrics-user");
        String key = "metrics-key-" + UUID.randomUUID();
        bookingService.confirm(key, hold.getId(), "metrics-user");
        bookingService.confirm(key, hold.getId(), "metrics-user"); // replay

        String body = rest.getForObject("/actuator/prometheus", String.class);

        assertThat(body).contains("seatbooking_operation_seconds_count{")
                .contains("operation=\"hold\"")
                .contains("operation=\"confirm\"")
                .contains("outcome=\"success\"")
                .contains("seatbooking_confirm_replays_total")
                .contains("seatbooking_outbox_unpublished")
                .contains("application=\"seat-booking-engine\"");
        assertThat(body).containsPattern("seatbooking_confirm_replays_total\\{[^}]*} [1-9]");
    }
}
