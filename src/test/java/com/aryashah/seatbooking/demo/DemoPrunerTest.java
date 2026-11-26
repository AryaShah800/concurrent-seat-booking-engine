package com.aryashah.seatbooking.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryashah.seatbooking.domain.Hold;
import com.aryashah.seatbooking.domain.Seat;
import com.aryashah.seatbooking.service.BookingService;
import com.aryashah.seatbooking.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"booking.demo.enabled=true", "booking.demo.prune-interval=PT24H"})
class DemoPrunerTest extends IntegrationTest {

    @Autowired DemoPruner pruner;
    @Autowired BookingService bookingService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void keepsOnlyTheNewestShowsAndDeletesTheirSeatsHoldsAndBookings() {
        Seat oldSeat = newSeat();
        Hold hold = bookingService.placeHold(oldSeat.getId(), "alice");
        bookingService.confirm("demo-prune-" + UUID.randomUUID(), hold.getId(), "alice");
        Seat keptA = newShowWithSeats(1, 2).get(0);
        Seat keptB = newShowWithSeats(2, 2).get(0);

        int removed = pruner.keepNewest(2);

        assertThat(removed).isGreaterThanOrEqualTo(1);
        List<Long> shows = jdbc.queryForList("SELECT id FROM shows ORDER BY id", Long.class);
        assertThat(shows).containsExactly(keptA.getShowId(), keptB.getShowId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE seat_id = ?", Long.class, oldSeat.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE id = ?", Long.class, oldSeat.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats", Long.class)).isEqualTo(6);
    }

    @Test
    void doesNothingWhenUnderTheLimit() {
        newSeat();
        long before = jdbc.queryForObject("SELECT count(*) FROM shows", Long.class);
        assertThat(pruner.keepNewest((int) before + 5)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shows", Long.class)).isEqualTo(before);
    }

    @Test
    void trimsOutboxRowsOlderThanAnHour() {
        UUID oldId = UUID.randomUUID();
        UUID freshId = UUID.randomUUID();
        String insert = "INSERT INTO outbox_event (id, aggregate_id, event_type, payload, created_at) "
                + "VALUES (?, '1', 'HoldPlaced', '{}'::jsonb, now() - (? * interval '1 minute'))";
        jdbc.update(insert, oldId, 90);
        jdbc.update(insert, freshId, 5);

        pruner.trimUnpublishedOutbox();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE id = ?", Long.class, oldId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE id = ?", Long.class, freshId)).isEqualTo(1);
    }
}
