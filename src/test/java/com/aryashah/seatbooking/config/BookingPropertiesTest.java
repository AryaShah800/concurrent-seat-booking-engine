package com.aryashah.seatbooking.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BookingPropertiesTest {

    @Test
    void defaultsToFiveMinutes() {
        assertThat(new BookingProperties(null).holdTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rejectsNonPositiveTtl() {
        assertThatThrownBy(() -> new BookingProperties(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BookingProperties(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
