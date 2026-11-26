package com.aryashah.seatbooking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aryashah.seatbooking.domain.Hold;
import com.aryashah.seatbooking.domain.HoldStatus;
import com.aryashah.seatbooking.domain.Seat;
import com.aryashah.seatbooking.domain.SeatStatus;
import com.aryashah.seatbooking.repository.HoldRepository;
import com.aryashah.seatbooking.service.ApiException;
import com.aryashah.seatbooking.service.BookingService;
import com.aryashah.seatbooking.service.BookingService.BookingResult;
import com.aryashah.seatbooking.support.IntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class BookingLifecycleTest extends IntegrationTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private HoldRepository holdRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private int outboxCount(String eventType, UUID holdId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ? AND payload->>'holdId' = ?",
                Integer.class, eventType, holdId.toString());
    }

    @Test
    void holdThenConfirm_booksTheSeat() {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "alice");

        BookingResult result = bookingService.confirm("key-confirm-1-" + UUID.randomUUID(), hold.getId(), "alice");

        assertThat(result.replayed()).isFalse();
        assertThat(result.booking().getSeatId()).isEqualTo(seat.getId());
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.BOOKED);
        assertThat(holdRepository.findById(hold.getId()).orElseThrow().getStatus()).isEqualTo(HoldStatus.CONFIRMED);
    }

    @Test
    void bookedSeat_cannotBeHeldAgain() {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "alice");
        bookingService.confirm("key-" + UUID.randomUUID(), hold.getId(), "alice");

        assertThatThrownBy(() -> bookingService.placeHold(seat.getId(), "bob"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("SEAT_UNAVAILABLE");
    }

    @Test
    void activeHold_blocksOtherCustomers() {
        Seat seat = newSeat();
        bookingService.placeHold(seat.getId(), "alice");

        assertThatThrownBy(() -> bookingService.placeHold(seat.getId(), "bob"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("SEAT_UNAVAILABLE");
    }

    @Test
    void expiredHold_letsAnotherCustomerTakeTheSeat_andCanNoLongerBeConfirmed() {
        Seat seat = newSeat();
        Hold aliceHold = bookingService.placeHold(seat.getId(), "alice");

        clock.advance(Duration.ofMinutes(2).plusSeconds(1));
        Hold bobHold = bookingService.placeHold(seat.getId(), "bob");

        assertThat(holdRepository.findById(aliceHold.getId()).orElseThrow().getStatus()).isEqualTo(HoldStatus.EXPIRED);
        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getHoldId()).isEqualTo(bobHold.getId());
        assertThatThrownBy(() -> bookingService.confirm("key-" + UUID.randomUUID(), aliceHold.getId(), "alice"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("HOLD_NOT_ACTIVE");
    }

    @Test
    void confirmAfterExpiry_isRejectedEvenBeforeTheSweeperRuns() {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "alice");
        clock.advance(Duration.ofMinutes(3));

        assertThatThrownBy(() -> bookingService.confirm("key-" + UUID.randomUUID(), hold.getId(), "alice"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("HOLD_NOT_ACTIVE");
    }

    @Test
    void sweeper_releasesSeatsWithExpiredHolds() {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "alice");
        clock.advance(Duration.ofMinutes(5));

        int released = bookingService.expireHolds();

        assertThat(released).isGreaterThanOrEqualTo(1);
        Seat reloaded = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(reloaded.getHoldId()).isNull();
        assertThat(holdRepository.findById(hold.getId()).orElseThrow().getStatus()).isEqualTo(HoldStatus.EXPIRED);
    }

    @Test
    void sweeper_leavesLiveHoldsAlone() {
        Seat seat = newSeat();
        bookingService.placeHold(seat.getId(), "alice");

        bookingService.expireHolds();

        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
    }

    @Test
    void releasingAHold_freesTheSeat() {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "alice");

        bookingService.releaseHold(hold.getId(), "alice");

        assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(holdRepository.findById(hold.getId()).orElseThrow().getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(bookingService.placeHold(seat.getId(), "bob")).isNotNull();
    }

    @Test
    void releasingTwice_isRejected() {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "alice");
        bookingService.releaseHold(hold.getId(), "alice");

        assertThatThrownBy(() -> bookingService.releaseHold(hold.getId(), "alice"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("HOLD_NOT_ACTIVE");
    }

    @Test
    void onlyTheHolderCanReleaseOrConfirm() {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "alice");

        assertThatThrownBy(() -> bookingService.releaseHold(hold.getId(), "mallory"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("HOLD_NOT_OWNED");
        assertThatThrownBy(() -> bookingService.confirm("key-" + UUID.randomUUID(), hold.getId(), "mallory"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("HOLD_NOT_OWNED");
    }

    @Test
    void retryWithSameKey_returnsTheOriginalBooking() {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "alice");
        String key = "key-" + UUID.randomUUID();

        BookingResult first = bookingService.confirm(key, hold.getId(), "alice");
        BookingResult second = bookingService.confirm(key, hold.getId(), "alice");

        assertThat(second.replayed()).isTrue();
        assertThat(second.booking().getId()).isEqualTo(first.booking().getId());
    }

    @Test
    void reusingAKeyForADifferentRequest_isRejected() {
        Seat seatA = newSeat();
        Seat seatB = newSeat();
        Hold holdA = bookingService.placeHold(seatA.getId(), "alice");
        Hold holdB = bookingService.placeHold(seatB.getId(), "alice");
        String key = "key-" + UUID.randomUUID();
        bookingService.confirm(key, holdA.getId(), "alice");

        assertThatThrownBy(() -> bookingService.confirm(key, holdB.getId(), "alice"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void unknownSeatOrHold_isNotFound() {
        assertThatThrownBy(() -> bookingService.placeHold(Long.MAX_VALUE, "alice"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("NOT_FOUND");
        assertThatThrownBy(() -> bookingService.confirm("key-" + UUID.randomUUID(), UUID.randomUUID(), "alice"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("NOT_FOUND");
        assertThatThrownBy(() -> bookingService.getBooking(UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("NOT_FOUND");
    }

    @Test
    void takingOverALapsedHold_publishesHoldExpiredOnce_andTheSweeperDoesNotRepeatIt() {
        Seat seat = newSeat();
        Hold aliceHold = bookingService.placeHold(seat.getId(), "alice");
        clock.advance(Duration.ofMinutes(2).plusSeconds(1));

        bookingService.placeHold(seat.getId(), "bob");
        bookingService.expireHolds();

        assertThat(outboxCount("HoldExpired", aliceHold.getId())).isEqualTo(1);
    }

    @Test
    void releasingAnExpiredHold_isRejectedEvenBeforeTheSweeperRuns() {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "alice");
        clock.advance(Duration.ofMinutes(3));

        assertThatThrownBy(() -> bookingService.releaseHold(hold.getId(), "alice"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("HOLD_NOT_ACTIVE");
        assertThat(outboxCount("HoldReleased", hold.getId())).isZero();
    }

    @Test
    void latestShow_isTheMostRecentlyCreatedOne() {
        Seat older = newSeat();
        Seat newer = newSeat();

        assertThat(showService.latestShow().orElseThrow().getId())
                .isEqualTo(seatRepository.findById(newer.getId()).orElseThrow().getShowId())
                .isNotEqualTo(seatRepository.findById(older.getId()).orElseThrow().getShowId());
    }
}
