package com.rls.gps.common.web;

import com.jayway.jsonpath.JsonPath;
import com.rls.gps.common.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.application.name=test-service"})
class CorrelationIdFilterTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void adoptsTheCorrelationIdSentByTheGateway() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(GpsHeaders.CORRELATION_ID, "corr-123");

        ResponseEntity<String> response = rest.exchange("/api/v1/boom", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst(GpsHeaders.CORRELATION_ID)).isEqualTo("corr-123");
        assertThat(JsonPath.<String>read(response.getBody(), "$.traceId")).isEqualTo("corr-123");
    }

    @Test
    void fallsBackToTheRequestIdHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(GpsHeaders.REQUEST_ID, "req-456");

        ResponseEntity<String> response = rest.exchange("/api/v1/boom", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst(GpsHeaders.CORRELATION_ID)).isEqualTo("req-456");
    }

    @Test
    void generatesOneWhenTheClientSendsNone() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/boom", String.class);

        assertThat(response.getHeaders().getFirst(GpsHeaders.CORRELATION_ID)).isNotBlank();
        assertThat(JsonPath.<String>read(response.getBody(), "$.traceId")).isNotBlank();
    }
}
