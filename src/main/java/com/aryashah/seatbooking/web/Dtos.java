package com.aryashah.seatbooking.web;

import com.aryashah.seatbooking.domain.Booking;
import com.aryashah.seatbooking.domain.Hold;
import com.aryashah.seatbooking.domain.Seat;
import com.aryashah.seatbooking.domain.SeatStatus;
import com.aryashah.seatbooking.domain.Show;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Request and response bodies for the REST API. */
public final class Dtos {

    private Dtos() {
    }

    public record CreateShowRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull Instant startsAt,
            @Min(1) @Max(26) int rows,
            @Min(1) @Max(100) int seatsPerRow) {
    }

    public record ShowResponse(Long id, String name, Instant startsAt) {
        static ShowResponse of(Show show) {
            return new ShowResponse(show.getId(), show.getName(), show.getStartsAt());
        }
    }

    /** A seat as clients should see it: a lapsed hold is reported as AVAILABLE. */
    public record SeatResponse(Long id, String label, SeatStatus status) {
        static SeatResponse of(Seat seat, Instant now) {
            SeatStatus effective = seat.getStatus() == SeatStatus.HELD && !seat.isUnavailableAt(now)
                    ? SeatStatus.AVAILABLE
                    : seat.getStatus();
            return new SeatResponse(seat.getId(), seat.getLabel(), effective);
        }
    }

    public record HoldRequest(@NotNull Long seatId, @NotBlank @Size(max = 100) String customerId) {
    }

    public record HoldResponse(UUID holdId, Long seatId, String customerId, Instant expiresAt) {
        static HoldResponse of(Hold hold) {
            return new HoldResponse(hold.getId(), hold.getSeatId(), hold.getCustomerId(), hold.getExpiresAt());
        }
    }

    public record ConfirmRequest(@NotNull UUID holdId, @NotBlank @Size(max = 100) String customerId) {
    }

    public record BookingResponse(UUID bookingId, Long seatId, UUID holdId, String customerId, Instant createdAt) {
        static BookingResponse of(Booking booking) {
            return new BookingResponse(booking.getId(), booking.getSeatId(), booking.getHoldId(),
                    booking.getCustomerId(), booking.getCreatedAt());
        }
    }
}
