package com.aryashah.seatbooking.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "booking.outbox")
public record OutboxProperties(
        @DefaultValue("booking.events") String topic,
        @DefaultValue("100") int batchSize,
        @DefaultValue("PT5S") Duration sendTimeout) {
}
