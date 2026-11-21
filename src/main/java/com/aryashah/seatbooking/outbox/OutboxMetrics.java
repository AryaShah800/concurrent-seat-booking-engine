package com.aryashah.seatbooking.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Outbox backlog: how many events are waiting for Kafka. A growing value means the broker is unreachable. */
@Component
public class OutboxMetrics implements MeterBinder {

    private final JdbcTemplate jdbc;

    public OutboxMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("seatbooking.outbox.unpublished", this, m -> m.unpublished())
                .description("Outbox events not yet acknowledged by Kafka")
                .register(registry);
    }

    private double unpublished() {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
