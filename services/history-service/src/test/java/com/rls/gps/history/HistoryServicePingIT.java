package com.rls.gps.history;

import com.jayway.jsonpath.JsonPath;
import com.rls.gps.history.support.AbstractHistoryServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service boots against a real database - which also proves the Flyway migration applies - and
 * answers the health endpoints Consul and Compose probe.
 *
 * <p>This was a plain @SpringBootTest until the datasource arrived. Unlike a Redis client, which
 * connects lazily, Flyway runs at startup: there is no honest way to ask "does it boot" without a
 * database, so the question moved here rather than being weakened.
 */
class HistoryServicePingIT extends AbstractHistoryServiceIT {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void pingIdentifiesTheService() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/ping", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.service")).isEqualTo("history-service");
        assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("UP");
    }

    @Test
    void healthIsUpWhenTheDatabaseIsReachable() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("UP");
    }
}
