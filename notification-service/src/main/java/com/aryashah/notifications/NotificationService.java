package com.aryashah.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final JdbcTemplate jdbc;

    public NotificationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event id and the side effect in one transaction.
     *
     * @return false if this event id was already processed (a redelivery)
     */
    @Transactional
    public boolean handle(UUID eventId, JsonNode event) {
        String type = event.get("eventType").asText();
        int inserted = jdbc.update(
                "INSERT INTO processed_event (event_id, event_type) VALUES (?, ?) ON CONFLICT DO NOTHING",
                eventId, type);
        if (inserted == 0) {
            return false;
        }
        if ("BookingConfirmed".equals(type) && event.hasNonNull("bookingId")) {
            long seatId = event.get("seatId").asLong();
            jdbc.update("INSERT INTO notification (event_id, booking_id, customer_id, seat_id, message) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    eventId, UUID.fromString(event.get("bookingId").asText()), event.get("customerId").asText(),
                    seatId, "Booking confirmed for seat " + seatId);
        }
        return true;
    }
}
