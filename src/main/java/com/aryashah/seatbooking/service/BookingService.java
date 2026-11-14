package com.aryashah.seatbooking.service;

import com.aryashah.seatbooking.config.BookingProperties;
import com.aryashah.seatbooking.domain.Booking;
import com.aryashah.seatbooking.domain.Hold;
import com.aryashah.seatbooking.domain.HoldStatus;
import com.aryashah.seatbooking.domain.Seat;
import com.aryashah.seatbooking.domain.SeatStatus;
import com.aryashah.seatbooking.repository.BookingRepository;
import com.aryashah.seatbooking.repository.HoldRepository;
import com.aryashah.seatbooking.repository.SeatRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seat holds and bookings.
 *
 * <p>Concurrency model:
 * <ul>
 *   <li><b>Holds</b>: optimistic locking on {@link Seat} through {@code @Version}. When N requests
 *       race for one seat they all read version v, but only one UPDATE ... WHERE version = v
 *       matches a row. The others get an optimistic-lock failure and a 409.</li>
 *   <li><b>Bookings</b>: confirming a hold also bumps the seat version, and PostgreSQL UNIQUE
 *       constraints on seat_id, hold_id and idempotency_key are a second safety net.</li>
 *   <li><b>Idempotency</b>: a confirm carries an Idempotency-Key. Repeating it with the same body
 *       returns the original booking. Reusing it with a different body is rejected (422).
 *       Two concurrent duplicates resolve to one booking: the loser's insert or update fails,
 *       and it then replays the winner's result.</li>
 * </ul>
 */
@Service
public class BookingService {

    private final SeatRepository seats;
    private final HoldRepository holds;
    private final BookingRepository bookings;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final BookingProperties properties;

    public BookingService(SeatRepository seats, HoldRepository holds, BookingRepository bookings,
                          PlatformTransactionManager transactionManager, Clock clock,
                          BookingProperties properties) {
        this.seats = seats;
        this.holds = holds;
        this.bookings = bookings;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties;
    }

    /** Places a time-limited hold on a seat, or throws 409 if someone else got it first. */
    public Hold placeHold(Long seatId, String customerId) {
        try {
            return tx.execute(status -> {
                Instant now = clock.instant();
                Seat seat = seats.findById(seatId).orElseThrow(() -> ApiException.notFound("Seat " + seatId));
                if (seat.isUnavailableAt(now)) {
                    throw ApiException.seatUnavailable(seatId);
                }
                if (seat.getStatus() == SeatStatus.HELD && seat.getHoldId() != null) {
                    // The previous hold lapsed, but the sweeper hasn't cleared it yet.
                    holds.findById(seat.getHoldId()).ifPresent(Hold::markExpired);
                }
                Hold hold = new Hold(UUID.randomUUID(), seatId, customerId, now, now.plus(properties.holdTtl()));
                seat.hold(hold.getId(), hold.getExpiresAt());
                holds.save(hold);
                seats.saveAndFlush(seat);
                return hold;
            });
        } catch (OptimisticLockingFailureException e) {
            throw ApiException.seatUnavailable(seatId);
        }
    }

    /** Releases a hold early so the seat returns to the pool. */
    public void releaseHold(UUID holdId, String customerId) {
        try {
            tx.executeWithoutResult(status -> {
                Hold hold = holds.findById(holdId).orElseThrow(() -> ApiException.notFound("Hold " + holdId));
                if (!hold.getCustomerId().equals(customerId)) {
                    throw ApiException.holdNotOwned();
                }
                if (hold.getStatus() != HoldStatus.ACTIVE) {
                    throw ApiException.holdNotActive();
                }
                hold.markReleased();
                Seat seat = seats.findById(hold.getSeatId()).orElseThrow();
                if (seat.getStatus() == SeatStatus.HELD && holdId.equals(seat.getHoldId())) {
                    seat.release();
                    seats.saveAndFlush(seat);
                }
            });
        } catch (OptimisticLockingFailureException e) {
            throw ApiException.holdNotActive();
        }
    }

    /**
     * Turns an active hold into a booking. Safe to retry with the same Idempotency-Key.
     *
     * @return the booking and whether it was replayed from an earlier request
     */
    public BookingResult confirm(String idempotencyKey, UUID holdId, String customerId) {
        String fingerprint = fingerprint(holdId, customerId);
        Optional<BookingResult> replay = replay(idempotencyKey, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            Booking booking = tx.execute(status -> {
                Instant now = clock.instant();
                Hold hold = holds.findById(holdId).orElseThrow(() -> ApiException.notFound("Hold " + holdId));
                if (!hold.getCustomerId().equals(customerId)) {
                    throw ApiException.holdNotOwned();
                }
                if (hold.getStatus() != HoldStatus.ACTIVE || hold.isExpiredAt(now)) {
                    throw ApiException.holdNotActive();
                }
                Seat seat = seats.findById(hold.getSeatId()).orElseThrow();
                if (seat.getStatus() != SeatStatus.HELD || !holdId.equals(seat.getHoldId())) {
                    throw ApiException.holdNotActive();
                }
                seat.book();
                hold.markConfirmed();
                Booking created = new Booking(UUID.randomUUID(), seat.getId(), holdId, customerId,
                        idempotencyKey, fingerprint, now);
                bookings.save(created);
                seats.saveAndFlush(seat);
                return created;
            });
            return new BookingResult(booking, false);
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
            // Another request committed first. If it used our key, return its booking.
            return replay(idempotencyKey, fingerprint).orElseThrow(ApiException::holdNotActive);
        } catch (ApiException e) {
            // A duplicate that passed the first replay check before the winner committed, and then
            // read the hold after that commit, sees it as CONFIRMED. If the winner used our key,
            // this is a retry and must get the original booking, not a 409.
            if ("HOLD_NOT_ACTIVE".equals(e.getCode())) {
                Optional<BookingResult> late = replay(idempotencyKey, fingerprint);
                if (late.isPresent()) {
                    return late.get();
                }
            }
            throw e;
        }
    }

    public Booking getBooking(UUID bookingId) {
        return bookings.findById(bookingId).orElseThrow(() -> ApiException.notFound("Booking " + bookingId));
    }

    /** Frees seats whose holds lapsed. Called on a schedule by {@link HoldExpiryJob}. */
    public int expireHolds() {
        Integer released = tx.execute(status -> {
            Instant now = clock.instant();
            holds.expireActiveHolds(now, HoldStatus.ACTIVE, HoldStatus.EXPIRED);
            return seats.releaseExpiredHolds(now, SeatStatus.HELD, SeatStatus.AVAILABLE);
        });
        return released == null ? 0 : released;
    }

    private Optional<BookingResult> replay(String idempotencyKey, String fingerprint) {
        return bookings.findByIdempotencyKey(idempotencyKey).map(existing -> {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw ApiException.idempotencyKeyReused();
            }
            return new BookingResult(existing, true);
        });
    }

    static String fingerprint(UUID holdId, String customerId) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest((holdId + "|" + customerId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record BookingResult(Booking booking, boolean replayed) {
    }
}
