package com.aryashah.seatbooking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A confirmed seat. The table has UNIQUE constraints on seat_id, hold_id and idempotency_key, so
 * the database itself refuses a second booking for the same seat.
 */
@Entity
@Table(name = "bookings")
public class Booking implements Persistable<UUID> {

    /** Ids are assigned by the app, so tell Spring Data to INSERT instead of merge-then-insert. */
    @Transient
    private boolean isNew = true;

    @Id
    private UUID id;

    @Column(name = "seat_id", nullable = false, unique = true)
    private Long seatId;

    @Column(name = "hold_id", nullable = false, unique = true)
    private UUID holdId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Booking() {
    }

    public Booking(UUID id, Long seatId, UUID holdId, String customerId, String idempotencyKey,
                   String requestFingerprint, Instant createdAt) {
        this.id = id;
        this.seatId = seatId;
        this.holdId = holdId;
        this.customerId = customerId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Long getSeatId() {
        return seatId;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
