package com.aryashah.seatbooking.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Appends an event to the outbox. Must be called inside the transaction that changes the seat,
 * so the event is stored if and only if the state change commits.
 */
@Component
public class OutboxWriter {

    public static final String HOLD_PLACED = "HoldPlaced";
    public static final String HOLD_RELEASED = "HoldReleased";
    public static final String HOLD_EXPIRED = "HoldExpired";
    public static final String BOOKING_CONFIRMED = "BookingConfirmed";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** @return the event id, which consumers use to de-duplicate redeliveries */
    public UUID write(String type, Long seatId, UUID holdId, UUID bookingId, String customerId) {
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("eventType", type);
        payload.put("occurredAt", now.toString());
        payload.put("seatId", seatId);
        payload.put("holdId", holdId == null ? null : holdId.toString());
        payload.put("bookingId", bookingId == null ? null : bookingId.toString());
        payload.put("customerId", customerId);
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise outbox payload", e);
        }
        jdbc.update("INSERT INTO outbox_event (id, aggregate_id, event_type, payload, created_at) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?)",
                eventId, String.valueOf(seatId), type, json, java.sql.Timestamp.from(now));
        return eventId;
    }
}
