package com.rls.gps.common.security;

import com.jayway.jsonpath.JsonPath;
import com.rls.gps.common.testapp.TestApplication;
import com.rls.gps.common.web.GpsHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.application.name=test-service"})
class IdentityResolutionTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void identityHeadersAreResolvedIntoTheController() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(GpsHeaders.COMPANY_ID, "company-1");
        headers.set(GpsHeaders.USER_ID, "user-9");
        headers.set(GpsHeaders.APP_KEY, "app-key-abc");

        ResponseEntity<String> response = rest.exchange("/api/v1/whoami", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.companyId")).isEqualTo("company-1");
        assertThat(JsonPath.<String>read(response.getBody(), "$.userId")).isEqualTo("user-9");
        assertThat(JsonPath.<String>read(response.getBody(), "$.appKey")).isEqualTo("app-key-abc");
    }

    @Test
    void missingIdentityIsRejectedWithProblemDetail() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/whoami", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("identity_required");
        assertThat(JsonPath.<String>read(response.getBody(), "$.traceId")).isNotBlank();
    }

    @Test
    void blankIdentityHeadersAreTreatedAsAbsent() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(GpsHeaders.COMPANY_ID, "   ");
        headers.set(GpsHeaders.USER_ID, "user-9");

        ResponseEntity<String> response = rest.exchange("/api/v1/whoami", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void optionalIdentityIsAllowedToBeAnonymous() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/whoami-optional", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<Object>read(response.getBody(), "$.companyId")).isNull();
    }
}
