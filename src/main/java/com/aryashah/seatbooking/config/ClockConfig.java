package com.aryashah.seatbooking.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** A single injectable clock so hold expiry can be tested deterministically. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
