package com.aryashah.seatbooking.outbox;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relays unpublished outbox rows to Kafka.
 *
 * <p>Each poll locks a batch with {@code FOR UPDATE SKIP LOCKED} (so several app instances never
 * publish the same row at the same time), sends it, waits for the broker ack, and only then
 * stamps {@code published_at}. If Kafka is down the send fails, the transaction rolls back and
 * the rows stay unpublished until the next poll. A crash after the ack but before the commit
 * re-sends the row, so delivery is at-least-once and consumers must de-duplicate on eventId.
 */
@Component
@ConditionalOnProperty(name = "booking.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OutboxProperties props;

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                           PlatformTransactionManager txManager, Clock clock, OutboxProperties props) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${booking.outbox.poll-interval:PT0.5S}")
    public void poll() {
        try {
            int sent;
            do {
                sent = publishBatch();
            } while (sent == props.batchSize());
        } catch (Exception e) {
            log.warn("Outbox publish failed, will retry: {}", e.toString());
        }
    }

    /** Publishes one batch and returns how many rows were sent. Throws if Kafka does not ack. */
    public int publishBatch() {
        Integer n = tx.execute(status -> {
            List<Row> rows = jdbc.query(
                    "SELECT id, aggregate_id, payload::text AS payload FROM outbox_event "
                            + "WHERE published_at IS NULL ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("aggregate_id"),
                            rs.getString("payload")),
                    props.batchSize());
            for (Row r : rows) {
                try {
                    kafka.send(props.topic(), r.aggregateId(), r.payload())
                            .get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("Kafka send failed for outbox event " + r.id(), e);
                }
                jdbc.update("UPDATE outbox_event SET published_at = ? WHERE id = ?",
                        java.sql.Timestamp.from(clock.instant()), r.id());
            }
            return rows.size();
        });
        return n == null ? 0 : n;
    }

    private record Row(UUID id, String aggregateId, String payload) {
    }
}
