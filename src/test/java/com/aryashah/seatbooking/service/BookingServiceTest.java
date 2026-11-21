package com.aryashah.seatbooking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aryashah.seatbooking.config.BookingProperties;
import com.aryashah.seatbooking.domain.Booking;
import com.aryashah.seatbooking.domain.Hold;
import com.aryashah.seatbooking.repository.BookingRepository;
import com.aryashah.seatbooking.repository.HoldRepository;
import com.aryashah.seatbooking.repository.SeatRepository;
import com.aryashah.seatbooking.service.BookingService.BookingResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Mockito unit tests for interleavings that a real race only hits now and then. These make
 * them deterministic.
 */
class BookingServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final SeatRepository seats = mock(SeatRepository.class);
    private final HoldRepository holds = mock(HoldRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final com.aryashah.seatbooking.outbox.OutboxWriter outbox = mock(com.aryashah.seatbooking.outbox.OutboxWriter.class);
    private BookingService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new BookingService(seats, holds, bookings, tm, Clock.fixed(NOW, ZoneOffset.UTC),
                new BookingProperties(Duration.ofMinutes(5)), outbox);
    }

    @Test
    void lateDuplicateThatSeesTheHoldAlreadyConfirmed_replaysTheWinnersBooking() {
        UUID holdId = UUID.randomUUID();
        Hold confirmed = new Hold(holdId, 7L, "alice", NOW, NOW.plusSeconds(300));
        confirmed.markConfirmed();
        Booking winner = new Booking(UUID.randomUUID(), 7L, holdId, "alice", "key-12345",
                BookingService.fingerprint(holdId, "alice"), NOW);
        // First replay check runs before the winner commits; the second one sees the booking.
        when(bookings.findByIdempotencyKey("key-12345")).thenReturn(Optional.empty(), Optional.of(winner));
        when(holds.findById(holdId)).thenReturn(Optional.of(confirmed));

        BookingResult result = service.confirm("key-12345", holdId, "alice");

        assertThat(result.replayed()).isTrue();
        assertThat(result.booking()).isSameAs(winner);
    }

    @Test
    void confirmingAnAlreadyConfirmedHoldWithANewKey_isStillAConflict() {
        UUID holdId = UUID.randomUUID();
        Hold confirmed = new Hold(holdId, 7L, "alice", NOW, NOW.plusSeconds(300));
        confirmed.markConfirmed();
        when(bookings.findByIdempotencyKey("other-key-1")).thenReturn(Optional.empty());
        when(holds.findById(holdId)).thenReturn(Optional.of(confirmed));

        assertThatThrownBy(() -> service.confirm("other-key-1", holdId, "alice"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("HOLD_NOT_ACTIVE");
    }
}
