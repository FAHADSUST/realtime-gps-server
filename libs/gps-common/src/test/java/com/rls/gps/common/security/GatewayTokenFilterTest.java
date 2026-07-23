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

/** The service must refuse traffic that did not come through Kong. */
@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.application.name=test-service",
                "gps.common.gateway.token=super-secret-gateway-token"
        })
class GatewayTokenFilterTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void requestWithoutGatewayTokenIsRejected() {
        HttpHeaders headers = identityHeaders();

        ResponseEntity<String> response = rest.exchange("/api/v1/whoami", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("gateway_token_invalid");
    }

    @Test
    void requestWithWrongGatewayTokenIsRejected() {
        HttpHeaders headers = identityHeaders();
        headers.set(GpsHeaders.GATEWAY_TOKEN, "not-the-token");

        ResponseEntity<String> response = rest.exchange("/api/v1/whoami", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void requestWithCorrectGatewayTokenIsAccepted() {
        HttpHeaders headers = identityHeaders();
        headers.set(GpsHeaders.GATEWAY_TOKEN, "super-secret-gateway-token");

        ResponseEntity<String> response = rest.exchange("/api/v1/whoami", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.userId")).isEqualTo("user-9");
    }

    @Test
    void healthEndpointsStayReachableWithoutTheToken() {
        assertThat(rest.getForEntity("/api/v1/ping", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static HttpHeaders identityHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(GpsHeaders.COMPANY_ID, "company-1");
        headers.set(GpsHeaders.USER_ID, "user-9");
        return headers;
    }
}
