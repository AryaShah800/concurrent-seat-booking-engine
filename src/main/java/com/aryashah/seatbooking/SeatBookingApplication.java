package com.aryashah.seatbooking;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class SeatBookingApplication {

    public static void main(String[] args) {
        // All timestamps are stored as TIMESTAMPTZ in UTC. A fixed JVM zone also avoids PostgreSQL
        // rejecting legacy zone ids such as "Asia/Calcutta" during the connection handshake.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(SeatBookingApplication.class, args);
    }
}
