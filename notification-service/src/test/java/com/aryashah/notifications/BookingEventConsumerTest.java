package com.aryashah.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
class BookingEventConsumerTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final SingleNodeKafka KAFKA = new SingleNodeKafka();

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;

    static String confirmed(UUID eventId, UUID bookingId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"BookingConfirmed\","
                + "\"occurredAt\":\"2026-01-01T10:00:00Z\",\"seatId\":7,\"holdId\":\"" + UUID.randomUUID()
                + "\",\"bookingId\":\"" + bookingId + "\",\"customerId\":\"alice\"}";
    }

    private int notificationsFor(UUID bookingId) {
        return jdbc.queryForObject("SELECT count(*) FROM notification WHERE booking_id = ?", Integer.class, bookingId);
    }

    @Test
    void confirmedBookingCreatesANotification() {
        UUID bookingId = UUID.randomUUID();
        kafka.send("booking.events", "7", confirmed(UUID.randomUUID(), bookingId));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(notificationsFor(bookingId)).isEqualTo(1));
    }

    @Test
    void redeliveredEventIsProcessedOnce() {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        String payload = confirmed(eventId, bookingId);
        kafka.send("booking.events", "7", payload);
        kafka.send("booking.events", "7", payload);
        kafka.send("booking.events", "7", payload);
        // A later, different event proves the duplicates before it were consumed.
        UUID marker = UUID.randomUUID();
        kafka.send("booking.events", "7", confirmed(UUID.randomUUID(), marker));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(notificationsFor(marker)).isEqualTo(1));

        assertThat(notificationsFor(bookingId)).isEqualTo(1);
        Integer processed = jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?",
                Integer.class, eventId);
        assertThat(processed).isEqualTo(1);
    }

    @Test
    void malformedMessageLandsOnTheDeadLetterTopicAndDoesNotBlockTheQueue() {
        String poison = "this is not json " + UUID.randomUUID();
        kafka.send("booking.events", "7", poison);
        UUID bookingId = UUID.randomUUID();
        kafka.send("booking.events", "7", confirmed(UUID.randomUUID(), bookingId));

        assertThat(readTopic("booking.events.DLT", poison)).hasSize(1);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(notificationsFor(bookingId)).isEqualTo(1));
    }

    @Test
    void contractSchemaAcceptsRealPayloadsAndRejectsBrokenOnes() throws Exception {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(Path.of("../contracts/booking-event.schema.json")));
        JsonNode good = mapper.readTree(confirmed(UUID.randomUUID(), UUID.randomUUID()));
        assertThat(schema.validate(good)).isEmpty();
        JsonNode noId = mapper.readTree("{\"eventType\":\"BookingConfirmed\",\"seatId\":1,\"customerId\":\"x\"}");
        assertThat(schema.validate(noId)).isNotEmpty();
    }

    private List<String> readTopic(String topic, String contains) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<String> found = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            c.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (found.isEmpty() && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(500))) {
                    if (r.value().contains(contains)) {
                        found.add(r.value());
                    }
                }
            }
        }
        return found;
    }
}
