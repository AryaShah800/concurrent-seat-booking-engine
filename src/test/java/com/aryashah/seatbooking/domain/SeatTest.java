package com.aryashah.seatbooking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plain unit tests for seat and hold state rules (no Spring, no database). */
class SeatTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void newSeat_isAvailable() {
        Seat seat = new Seat(1L, "A1");
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.isUnavailableAt(NOW)).isFalse();
    }

    @Test
    void liveHold_makesSeatUnavailable_untilItExpires() {
        Seat seat = new Seat(1L, "A1");
        seat.hold(UUID.randomUUID(), NOW.plusSeconds(60));

        assertThat(seat.isUnavailableAt(NOW)).isTrue();
        assertThat(seat.isUnavailableAt(NOW.plusSeconds(59))).isTrue();
        assertThat(seat.isUnavailableAt(NOW.plusSeconds(60))).isFalse();
    }

    @Test
    void bookedSeat_isAlwaysUnavailable() {
        Seat seat = new Seat(1L, "A1");
        seat.hold(UUID.randomUUID(), NOW.plusSeconds(60));
        seat.book();

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.BOOKED);
        assertThat(seat.isUnavailableAt(NOW.plusSeconds(3600))).isTrue();
    }

    @Test
    void release_clearsTheHold() {
        Seat seat = new Seat(1L, "A1");
        seat.hold(UUID.randomUUID(), NOW.plusSeconds(60));
        seat.release();

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getHoldId()).isNull();
        assertThat(seat.getHoldExpiresAt()).isNull();
    }

    @Test
    void holdExpiry_isInclusiveOfTheExpiryInstant() {
        Hold hold = new Hold(UUID.randomUUID(), 1L, "alice", NOW, NOW.plusSeconds(30));

        assertThat(hold.isExpiredAt(NOW.plusSeconds(29))).isFalse();
        assertThat(hold.isExpiredAt(NOW.plusSeconds(30))).isTrue();
        assertThat(hold.isNew()).isTrue();
    }
}
