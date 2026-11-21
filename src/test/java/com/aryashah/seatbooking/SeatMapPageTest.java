package com.aryashah.seatbooking;

import static org.assertj.core.api.Assertions.assertThat;

import com.aryashah.seatbooking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

class SeatMapPageTest extends IntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void seatMapIsServedAtTheRoot() {
        ResponseEntity<String> res = rest.getForEntity("/", String.class);

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(res.getBody()).contains("<title>Seat map</title>").contains("/api/holds");
    }
}
