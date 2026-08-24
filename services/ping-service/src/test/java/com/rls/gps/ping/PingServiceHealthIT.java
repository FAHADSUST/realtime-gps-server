package com.rls.gps.ping;

import com.jayway.jsonpath.JsonPath;
import com.rls.gps.ping.support.AbstractPingServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With a real Redis, the service reports healthy - which is what Consul and Compose probe, and what
 * decides whether traffic is sent here.
 */
class PingServiceHealthIT extends AbstractPingServiceIT {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthIsUpWhenRedisIsReachable() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("UP");
    }
}
