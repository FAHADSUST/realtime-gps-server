package com.rls.gps.common.error;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.rls.gps.common.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.application.name=test-service"})
class ProblemDetailErrorsTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void apiExceptionsBecomeProblemDetails() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/boom", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("thing_not_found");
        assertThat(JsonPath.<String>read(response.getBody(), "$.detail")).isEqualTo("No such thing");
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.status")).isEqualTo(404);
        assertThat(JsonPath.<String>read(response.getBody(), "$.timestamp")).isNotBlank();
    }

    @Test
    void unexpectedExceptionsDoNotLeakInternals() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/explode", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("internal_error");
        assertThat(response.getBody()).doesNotContain("unexpected failure");
    }

    @Test
    void validationFailuresListEveryOffendingField() {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/echo",
                Map.of("name", "", "count", 0), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("validation_failed");
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.errors"))
                .hasSize(2)
                .anySatisfy(error -> assertThat(error).startsWith("count:"))
                .anySatisfy(error -> assertThat(error).startsWith("name:"));
    }

    @Test
    void unsupportedMethodsAlsoRenderAsProblemDetails() {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/boom", Map.of(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("bad_request");
    }
}
