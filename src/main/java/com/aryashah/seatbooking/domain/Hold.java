package com.aryashah.seatbooking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "holds")
public class Hold implements Persistable<UUID> {

    /** Ids are assigned by the app, so tell Spring Data to INSERT instead of merge-then-insert. */
    @Transient
    private boolean isNew = true;

    @Id
    private UUID id;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status = HoldStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Hold() {
    }

    public Hold(UUID id, Long seatId, String customerId, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.seatId = seatId;
        this.customerId = customerId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void markConfirmed() {
        this.status = HoldStatus.CONFIRMED;
    }

    public void markReleased() {
        this.status = HoldStatus.RELEASED;
    }

    public void markExpired() {
        this.status = HoldStatus.EXPIRED;
    }

    public UUID getId() {
        return id;
    }

    public Long getSeatId() {
        return seatId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public HoldStatus getStatus() {
        return status;
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
