package com.aryashah.seatbooking.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Metrics for the booking flow, exported to Prometheus.
 *
 * <ul>
 *   <li>{@code seatbooking_operation_seconds{operation,outcome}}: latency of hold / release / confirm
 *       with outcome {@code success}, {@code rejected} (an expected 4xx such as a lost race) or {@code error}.</li>
 *   <li>{@code seatbooking_confirm_replays_total}: confirms answered from an earlier request (idempotency).</li>
 *   <li>{@code seatbooking_holds_expired_total}: seats freed by the sweeper.</li>
 * </ul>
 */
@Component
public class BookingMetrics {

    private final MeterRegistry registry;
    private final Counter replays;
    private final Counter expired;

    public BookingMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.replays = Counter.builder("seatbooking.confirm.replays")
                .description("Confirm requests answered from an earlier request with the same Idempotency-Key")
                .register(registry);
        this.expired = Counter.builder("seatbooking.holds.expired")
                .description("Seats released because their hold lapsed")
                .register(registry);
    }

    public <T> T time(String operation, Supplier<T> action) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return action.get();
        } catch (ApiException e) {
            outcome = "rejected";
            throw e;
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(Timer.builder("seatbooking.operation")
                    .description("Latency of booking operations")
                    .publishPercentileHistogram()
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }

    public void replayed() {
        replays.increment();
    }

    public void expired(int n) {
        expired.increment(n);
    }
}
