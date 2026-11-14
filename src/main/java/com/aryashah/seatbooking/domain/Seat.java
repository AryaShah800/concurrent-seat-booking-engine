package com.aryashah.seatbooking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A seat for one show. Every state change bumps {@link #version}, so two transactions that read
 * the same seat cannot both change it: the second commit fails with an optimistic-lock error.
 */
@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Column(nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Seat() {
    }

    public Seat(Long showId, String label) {
        this.showId = showId;
        this.label = label;
    }

    /** True when nobody can claim this seat at {@code now}. An expired hold counts as free. */
    public boolean isUnavailableAt(Instant now) {
        return switch (status) {
            case AVAILABLE -> false;
            case BOOKED -> true;
            case HELD -> holdExpiresAt != null && holdExpiresAt.isAfter(now);
        };
    }

    public void hold(UUID newHoldId, Instant expiresAt) {
        this.status = SeatStatus.HELD;
        this.holdId = newHoldId;
        this.holdExpiresAt = expiresAt;
    }

    public void book() {
        this.status = SeatStatus.BOOKED;
        this.holdExpiresAt = null;
    }

    public void release() {
        this.status = SeatStatus.AVAILABLE;
        this.holdId = null;
        this.holdExpiresAt = null;
    }

    public Long getId() {
        return id;
    }

    public Long getShowId() {
        return showId;
    }

    public String getLabel() {
        return label;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public long getVersion() {
        return version;
    }
}
