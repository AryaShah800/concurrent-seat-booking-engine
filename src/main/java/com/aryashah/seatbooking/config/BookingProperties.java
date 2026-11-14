package com.aryashah.seatbooking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for seat holds.
 *
 * @param holdTtl how long a hold reserves a seat before someone else can take it
 */
@ConfigurationProperties(prefix = "booking")
public record BookingProperties(Duration holdTtl) {

    public BookingProperties {
        if (holdTtl == null) {
            holdTtl = Duration.ofMinutes(5);
        }
        if (holdTtl.isNegative() || holdTtl.isZero()) {
            throw new IllegalArgumentException("booking.hold-ttl must be positive");
        }
    }
}
