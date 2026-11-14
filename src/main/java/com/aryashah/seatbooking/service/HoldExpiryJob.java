package com.aryashah.seatbooking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Releases seats whose holds have lapsed, so they return to the pool. */
@Component
@ConditionalOnProperty(name = "booking.sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final BookingService bookingService;

    public HoldExpiryJob(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Scheduled(fixedDelayString = "${booking.sweep-interval:PT5S}")
    public void sweep() {
        int released = bookingService.expireHolds();
        if (released > 0) {
            log.info("Released {} seat(s) with expired holds", released);
        }
    }
}
