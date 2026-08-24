package com.rls.gps.ping;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service boots and answers the liveness endpoint without any infrastructure.
 *
 * <p>The Redis health indicator is switched off here on purpose: this test asks "does the
 * application start and serve {@code /api/v1/ping}", and the answer should not depend on a
 * container. Whether Redis is actually reachable and healthy is
 * {@link com.rls.gps.ping.PingServiceHealthIT}'s job.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.consul.enabled=false",
                "spring.cloud.consul.config.enabled=false",
                "spring.cloud.consul.discovery.enabled=false",
                "management.health.redis.enabled=false"
        })
class PingServiceApplicationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void pingIdentifiesTheService() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/ping", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.service")).isEqualTo("ping-service");
        assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("UP");
    }
}
