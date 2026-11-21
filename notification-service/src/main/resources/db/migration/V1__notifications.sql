-- processed_event gives exactly-once effects on top of at-least-once delivery:
-- the event id is inserted in the same transaction as the side effect.
CREATE TABLE processed_event (
    event_id     UUID PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notification (
    id          BIGSERIAL PRIMARY KEY,
    event_id    UUID         NOT NULL REFERENCES processed_event (event_id),
    booking_id  UUID         NOT NULL,
    customer_id VARCHAR(100) NOT NULL,
    seat_id     BIGINT       NOT NULL,
    message     VARCHAR(300) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
