-- Transactional outbox: rows are written in the same transaction as the state change they
-- describe, then relayed to Kafka by OutboxPublisher (at-least-once).
CREATE TABLE outbox_event (
    id           UUID PRIMARY KEY,
    aggregate_id VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX ix_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;
