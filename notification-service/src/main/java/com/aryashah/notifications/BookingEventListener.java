package com.aryashah.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BookingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BookingEventListener.class);

    private final ObjectMapper mapper;
    private final NotificationService service;

    public BookingEventListener(ObjectMapper mapper, NotificationService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @KafkaListener(topics = "${notifications.topic}")
    public void onEvent(String payload) {
        JsonNode event;
        UUID eventId;
        try {
            event = mapper.readTree(payload);
            eventId = UUID.fromString(event.get("eventId").asText());
            if (!event.hasNonNull("eventType") || !event.hasNonNull("customerId") || !event.hasNonNull("seatId")) {
                throw new IllegalArgumentException("missing required field");
            }
        } catch (Exception e) {
            throw new PoisonMessageException("Unreadable booking event", e);
        }
        if (!service.handle(eventId, event)) {
            log.info("Duplicate event {} ignored", eventId);
        }
    }
}
