package com.aryashah.seatbooking.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OutboxPublisherBatchTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private OutboxPublisher publisher() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new OutboxPublisher(jdbc, kafka, tm, Clock.systemUTC(),
                new OutboxProperties("booking.events", 10, Duration.ofSeconds(1), Duration.ofDays(7)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void rows(int n) {
        when(jdbc.query(anyString(), any(RowMapper.class), anyInt())).thenAnswer(inv -> {
            RowMapper<Object> mapper = inv.getArgument(1);
            ArrayList<Object> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
                when(rs.getString("aggregate_id")).thenReturn("1");
                when(rs.getString("payload")).thenReturn("{}");
                out.add(mapper.mapRow(rs, i));
            }
            return out;
        });
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    @Test
    void partialBatchFailure_marksNothingPublished() {
        rows(3);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(ok())
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker said no")))
                .thenReturn(ok());

        assertThatThrownBy(() -> publisher().publishBatch()).isInstanceOf(IllegalStateException.class);

        verify(jdbc, never()).update(anyString(), ArgumentMatchers.<PreparedStatementSetter>any());
    }

    @Test
    void fullyAckedBatch_isMarkedPublishedInOneUpdate() {
        rows(3);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(ok());

        assertThat(publisher().publishBatch()).isEqualTo(3);

        verify(jdbc).update(anyString(), ArgumentMatchers.<PreparedStatementSetter>any());
    }
}
