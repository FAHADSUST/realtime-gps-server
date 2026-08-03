package com.rls.gps.id;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service boots without Consul and answers the health endpoint the spec requires of every
 * service - which it inherits from gps-common rather than declaring itself.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.consul.enabled=false",
                "spring.cloud.consul.config.enabled=false",
                "spring.cloud.consul.discovery.enabled=false"
        })
class IdServiceApplicationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void pingIdentifiesTheService() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/ping", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.service")).isEqualTo("id-service");
        assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("UP");
    }

    @Test
    void actuatorHealthIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("UP");
    }
}
