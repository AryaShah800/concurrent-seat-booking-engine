package com.aryashah.seatbooking.outbox;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes published outbox rows older than the retention period so the table does not grow forever. */
@Component
@ConditionalOnProperty(name = "booking.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPurger {

    private static final Logger log = LoggerFactory.getLogger(OutboxPurger.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OutboxProperties props;

    public OutboxPurger(JdbcTemplate jdbc, Clock clock, OutboxProperties props) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${booking.outbox.purge-interval:PT1H}", initialDelayString = "PT1M")
    public void scheduledPurge() {
        try {
            int n = purge();
            if (n > 0) {
                log.info("Purged {} published outbox rows", n);
            }
        } catch (Exception e) {
            log.warn("Outbox purge failed: {}", e.toString());
        }
    }

    /** @return rows deleted. Unpublished rows are never deleted. */
    public int purge() {
        return jdbc.update("DELETE FROM outbox_event WHERE published_at IS NOT NULL AND published_at < ?",
                java.sql.Timestamp.from(clock.instant().minus(props.retention())));
    }
}
