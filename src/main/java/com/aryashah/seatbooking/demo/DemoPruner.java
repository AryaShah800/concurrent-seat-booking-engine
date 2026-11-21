package com.aryashah.seatbooking.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public-demo guard: keeps only the newest {@code booking.demo.max-shows} shows (with their seats, holds and
 * bookings), so anonymous visitors creating shows cannot grow a free-tier database without bound.
 * Off unless {@code booking.demo.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "booking.demo.enabled", havingValue = "true")
public class DemoPruner {

    private static final Logger log = LoggerFactory.getLogger(DemoPruner.class);

    private final JdbcTemplate jdbc;
    private final int maxShows;

    public DemoPruner(JdbcTemplate jdbc, @Value("${booking.demo.max-shows:50}") int maxShows) {
        this.jdbc = jdbc;
        this.maxShows = maxShows;
    }

    @Scheduled(fixedDelayString = "${booking.demo.prune-interval:PT10M}")
    public void scheduledPrune() {
        int removed = keepNewest(maxShows);
        if (removed > 0) {
            log.info("Demo pruning removed {} old show(s)", removed);
        }
    }

    /** Deletes every show except the newest {@code keep}; returns the number of shows removed. */
    @Transactional
    public int keepNewest(int keep) {
        String old = "SELECT id FROM shows ORDER BY id DESC OFFSET ?";
        jdbc.update("DELETE FROM bookings WHERE seat_id IN (SELECT id FROM seats WHERE show_id IN (" + old + "))", keep);
        jdbc.update("DELETE FROM holds WHERE seat_id IN (SELECT id FROM seats WHERE show_id IN (" + old + "))", keep);
        jdbc.update("DELETE FROM seats WHERE show_id IN (" + old + ")", keep);
        return jdbc.update("DELETE FROM shows WHERE id IN (" + old + ")", keep);
    }
}
