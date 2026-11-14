CREATE TABLE shows (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    starts_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE seats (
    id              BIGSERIAL PRIMARY KEY,
    show_id         BIGINT      NOT NULL REFERENCES shows (id),
    label           VARCHAR(16) NOT NULL,
    status          VARCHAR(16) NOT NULL CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    hold_id         UUID,
    hold_expires_at TIMESTAMPTZ,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_seats_show_label UNIQUE (show_id, label)
);

CREATE INDEX ix_seats_show ON seats (show_id);
CREATE INDEX ix_seats_held_expiry ON seats (hold_expires_at) WHERE status = 'HELD';

CREATE TABLE holds (
    id          UUID PRIMARY KEY,
    seat_id     BIGINT       NOT NULL REFERENCES seats (id),
    customer_id VARCHAR(100) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    status      VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_holds_active_expiry ON holds (expires_at) WHERE status = 'ACTIVE';

-- The UNIQUE constraints are the last line of defence: whatever the application does,
-- PostgreSQL will not store two bookings for one seat, one hold or one idempotency key.
CREATE TABLE bookings (
    id                  UUID PRIMARY KEY,
    seat_id             BIGINT       NOT NULL REFERENCES seats (id),
    hold_id             UUID         NOT NULL REFERENCES holds (id),
    customer_id         VARCHAR(100) NOT NULL,
    idempotency_key     VARCHAR(100) NOT NULL,
    request_fingerprint VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_bookings_seat UNIQUE (seat_id),
    CONSTRAINT uq_bookings_hold UNIQUE (hold_id),
    CONSTRAINT uq_bookings_idempotency_key UNIQUE (idempotency_key)
);
