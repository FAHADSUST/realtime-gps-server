package com.rls.gps.common.web;

import com.jayway.jsonpath.JsonPath;
import com.rls.gps.common.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.application.name=test-service"})
class PingEndpointTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void pingReportsServiceNameStatusAndTime() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/ping", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.service")).isEqualTo("test-service");
        assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("UP");
        assertThat(JsonPath.<String>read(response.getBody(), "$.version")).isNotBlank();
        assertThat(JsonPath.<String>read(response.getBody(), "$.timestamp")).isNotBlank();
    }
}
