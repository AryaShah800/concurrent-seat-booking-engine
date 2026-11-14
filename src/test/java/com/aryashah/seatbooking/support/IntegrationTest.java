package com.aryashah.seatbooking.support;

import com.aryashah.seatbooking.domain.Seat;
import com.aryashah.seatbooking.domain.Show;
import com.aryashah.seatbooking.repository.SeatRepository;
import com.aryashah.seatbooking.service.ShowService;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need the full application on a real PostgreSQL 16 (Testcontainers).
 * The container is started once per JVM and shared by every test class, so the Spring context
 * is cached across classes too.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"booking.sweeper.enabled=false", "booking.hold-ttl=PT2M"})
@Import(IntegrationTest.TestClockConfig.class)
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("seatbooking")
            .withUsername("seatbooking")
            .withPassword("seatbooking")
            .withCommand("postgres", "-c", "max_connections=200");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "60");
    }

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected ShowService showService;

    @Autowired
    protected SeatRepository seatRepository;

    protected List<Seat> newShowWithSeats(int rows, int seatsPerRow) {
        Show show = showService.createShow("Test show", clock.instant().plusSeconds(86_400), rows, seatsPerRow);
        return seatRepository.findByShowIdOrderByIdAsc(show.getId());
    }

    protected Seat newSeat() {
        return newShowWithSeats(1, 1).get(0);
    }

    @TestConfiguration
    static class TestClockConfig {
        /** Replaces the system clock everywhere a {@link java.time.Clock} is injected. */
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        }
    }
}
