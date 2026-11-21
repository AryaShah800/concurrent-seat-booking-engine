package com.aryashah.notifications;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic bookingEventsTopic() {
        return new NewTopic("booking.events", 1, (short) 1);
    }

    @Bean
    NewTopic bookingEventsDeadLetterTopic() {
        return new NewTopic("booking.events.DLT", 1, (short) 1);
    }

    /**
     * Transient failures (for example the database being down) are retried 3 times, one second
     * apart, then the record is parked on {@code <topic>.DLT}. Poison messages skip the retries.
     */
    @Bean
    CommonErrorHandler errorHandler(KafkaOperations<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", 0));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
        handler.addNotRetryableExceptions(PoisonMessageException.class);
        return handler;
    }
}
