package com.aryashah.seatbooking.outbox;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
 * <p>Only one instance relays at a time: each batch first takes a transaction-scoped PostgreSQL
 * advisory lock and skips the poll if another instance holds it. With SKIP LOCKED alone, two
 * instances could take different batches and publish a seat's later event (HoldExpired) before
 * its earlier one (HoldPlaced), which breaks per-seat ordering for consumers.
 *
 * <p>The batch is locked with {@code FOR UPDATE SKIP LOCKED}, sent, the broker ack awaited, and only then
 * stamps {@code published_at}. If Kafka is down the send fails, the transaction rolls back and
 * the rows stay unpublished until the next poll. A crash after the ack but before the commit
 * re-sends the row, so delivery is at-least-once and consumers must de-duplicate on eventId.
 */
@Component
@ConditionalOnProperty(name = "booking.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /** Arbitrary constant key for pg_try_advisory_xact_lock: "the outbox relay". */
    static final long RELAY_LOCK_KEY = 0x5EA7_B00C_0001L;

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
            // Released automatically at commit or rollback, so a crashed relay never holds it.
            Boolean relay = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, RELAY_LOCK_KEY);
            if (!Boolean.TRUE.equals(relay)) {
                return 0; // another instance is relaying right now
            }
            List<Row> rows = jdbc.query(
                    "SELECT id, aggregate_id, payload::text AS payload FROM outbox_event "
                            + "WHERE published_at IS NULL ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("aggregate_id"),
                            rs.getString("payload")),
                    props.batchSize());
            if (rows.isEmpty()) {
                return 0;
            }
            // Send the whole batch without blocking per row, then wait for every ack.
            List<CompletableFuture<?>> acks = new ArrayList<>(rows.size());
            for (Row r : rows) {
                acks.add(kafka.send(props.topic(), r.aggregateId(), r.payload()));
            }
            try {
                CompletableFuture.allOf(acks.toArray(new CompletableFuture<?>[0]))
                        .get(props.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // Any failure rolls the transaction back: nothing is marked published and the
                // whole batch is retried (some rows may then be delivered twice: at-least-once).
                throw new IllegalStateException("Kafka send failed for outbox batch of " + rows.size(), e);
            }
            UUID[] ids = rows.stream().map(Row::id).toArray(UUID[]::new);
            jdbc.update("UPDATE outbox_event SET published_at = ? WHERE id = ANY (?)",
                    ps -> {
                        ps.setTimestamp(1, java.sql.Timestamp.from(clock.instant()));
                        ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids));
                    });
            return rows.size();
        });
        return n == null ? 0 : n;
    }

    private record Row(UUID id, String aggregateId, String payload) {
    }
}
