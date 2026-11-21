package com.aryashah.seatbooking.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.aryashah.seatbooking.domain.Hold;
import com.aryashah.seatbooking.domain.Seat;
import com.aryashah.seatbooking.service.BookingService;
import com.aryashah.seatbooking.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import com.aryashah.seatbooking.support.SingleNodeKafka;

/** Outbox to Kafka on a real broker (Testcontainers) and a real PostgreSQL. */
@TestPropertySource(properties = {"booking.outbox.enabled=true", "booking.outbox.poll-interval=PT0.2S"})
class OutboxKafkaTest extends IntegrationTest {

    static final SingleNodeKafka KAFKA = new SingleNodeKafka();

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @AfterAll
    static void unpauseIfNeeded() {
        try {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        } catch (Exception ignored) {
            // already running
        }
    }

    @Autowired
    BookingService bookingService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    OutboxPurger purger;

    @Test
    void confirmedBookingIsPublishedExactlyOnceWithAnEventId() {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "alice");
        var booking = bookingService.confirm("key-" + UUID.randomUUID(), hold.getId(), "alice").booking();

        List<JsonNode> seen = readEvents(booking.getId().toString(), "BookingConfirmed");
        assertThat(seen).hasSize(1);
        JsonNode e = seen.get(0);
        assertThat(e.get("eventId").asText()).isNotBlank();
        // Contract shared with notification-service: the real payload must satisfy contracts/booking-event.schema.json.
        try {
            var schema = com.networknt.schema.JsonSchemaFactory
                    .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                    .getSchema(java.nio.file.Files.readString(java.nio.file.Path.of("contracts/booking-event.schema.json")));
            assertThat(schema.validate(e)).isEmpty();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        assertThat(e.get("seatId").asLong()).isEqualTo(seat.getId());
        assertThat(e.get("customerId").asText()).isEqualTo("alice");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(unpublishedFor(seat.getId())).isZero());
    }

    @Test
    void eventsWrittenWhileKafkaIsUnreachableAreDeliveredAfterItRecovers() {
        Seat during = newSeat();
        UUID bookingId;
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            Hold hold = bookingService.placeHold(during.getId(), "bob");
            bookingId = bookingService.confirm("key-" + UUID.randomUUID(), hold.getId(), "bob").booking().getId();
            // The booking API keeps working and the two events wait in the outbox.
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(unpublishedFor(during.getId())).isEqualTo(2));
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(unpublishedFor(during.getId())).isZero());
        assertThat(readEvents(bookingId.toString(), "BookingConfirmed")).hasSize(1);
    }

    @Test
    void failedConfirmLeavesNoEventBehind() {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "carol");
        assertThatThrownBy(() -> bookingService.confirm("key-" + UUID.randomUUID(), hold.getId(), "mallory"))
                .isInstanceOf(RuntimeException.class);
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'BookingConfirmed' AND payload->>'holdId' = ?",
                Integer.class, hold.getId().toString());
        assertThat(n).isZero();
    }

    @Test
    void expiredHoldProducesAHoldExpiredEvent() {
        Seat seat = newSeat();
        Hold hold = bookingService.placeHold(seat.getId(), "dave");
        clock.advance(Duration.ofMinutes(3));
        assertThat(bookingService.expireHolds()).isGreaterThanOrEqualTo(1);
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'HoldExpired' AND payload->>'holdId' = ?",
                Integer.class, hold.getId().toString());
        assertThat(n).isEqualTo(1);
    }

    @Test
    void purgeDeletesOnlyOldPublishedRows() {
        UUID oldPublished = UUID.randomUUID();
        UUID recentPublished = UUID.randomUUID();
        UUID oldUnpublished = UUID.randomUUID();
        String sql = "INSERT INTO outbox_event (id, aggregate_id, event_type, payload, created_at, published_at) "
                + "VALUES (?, '9', 'X', '{}'::jsonb, now(), ?)";
        jdbc.update(sql, oldPublished, java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(30))));
        jdbc.update(sql, recentPublished, java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(1))));
        jdbc.update(sql, oldUnpublished, null);

        purger.purge();

        assertThat(exists(oldPublished)).isFalse();
        assertThat(exists(recentPublished)).isTrue();
        assertThat(exists(oldUnpublished)).isTrue();
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE id = ?", Integer.class, id) > 0;
    }

    private int unpublishedFor(Long seatId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NULL AND aggregate_id = ?",
                Integer.class, String.valueOf(seatId));
    }

    /** Reads the topic from the start and returns every event of a type for the booking id. */
    private List<JsonNode> readEvents(String bookingId, String type) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<JsonNode> matches = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            consumer.subscribe(List.of("booking.events"));
            long deadline = System.currentTimeMillis() + 30_000;
            boolean found = false;
            while (System.currentTimeMillis() < deadline) {
                // After the first match, one more poll window catches duplicates.
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(found ? 2000 : 500))) {
                    try {
                        JsonNode n = mapper.readTree(r.value());
                        if (type.equals(n.path("eventType").asText())
                                && bookingId.equals(n.path("bookingId").asText())) {
                            matches.add(n);
                            found = true;
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                if (found) {
                    consumer.poll(Duration.ofSeconds(2)).forEach(r -> {
                        try {
                            JsonNode n = mapper.readTree(r.value());
                            if (type.equals(n.path("eventType").asText())
                                    && bookingId.equals(n.path("bookingId").asText())) {
                                matches.add(n);
                            }
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    break;
                }
            }
        }
        return matches;
    }
}
