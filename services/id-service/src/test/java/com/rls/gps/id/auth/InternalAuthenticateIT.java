package com.rls.gps.id.auth;

import java.time.Instant;

import com.jayway.jsonpath.JsonPath;
import com.rls.gps.common.web.GpsHeaders;
import com.rls.gps.id.support.AbstractIdServiceIT;
import com.rls.gps.id.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** What Kong's rls_auth plugin will call on every proxied request. */
class InternalAuthenticateIT extends AbstractIdServiceIT {

    private static final String PASSWORD = "s3cret-password";

    private RegisteredCompany acme;
    private String driverId;
    private String token;

    @BeforeEach
    void seed() {
        acme = registerCompany("Acme Logistics");
        driverId = registerUser(acme, "driver-1", PASSWORD);
        token = obtainToken(acme, "driver-1", PASSWORD);
    }

    @Test
    void returnsTheIdentityAsHeadersAndBody() {
        ResponseEntity<String> response = authenticate("Bearer " + token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getFirst(GpsHeaders.COMPANY_ID)).isEqualTo(acme.companyId());
        assertThat(headers.getFirst(GpsHeaders.USER_ID)).isEqualTo(driverId);
        assertThat(headers.getFirst(GpsHeaders.APP_KEY)).isEqualTo(acme.appKey());
        assertThat(Long.parseLong(headers.getFirst(GpsHeaders.TOKEN_EXPIRES_IN)))
                .isPositive()
                .isLessThanOrEqualTo(3600);

        String body = response.getBody();
        assertThat(JsonPath.<String>read(body, "$.companyId")).isEqualTo(acme.companyId());
        assertThat(JsonPath.<String>read(body, "$.userId")).isEqualTo(driverId);
        assertThat(JsonPath.<String>read(body, "$.expiresAt")).isNotBlank();
    }

    @Test
    void acceptsTheSchemeInAnyCase() {
        assertThat(authenticate("bearer " + token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authenticate("BEARER " + token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rejectsAMissingOrMalformedHeader() {
        assertThat(codeOf(authenticate(null))).isEqualTo("token_missing");
        assertThat(codeOf(authenticate("   "))).isEqualTo("token_missing");
        assertThat(codeOf(authenticate(token))).isEqualTo("token_missing");
        assertThat(codeOf(authenticate("Basic " + token))).isEqualTo("token_missing");
    }

    @Test
    void rejectsAGarbageOrTamperedToken() {
        assertThat(codeOf(authenticate("Bearer not-a-jwt"))).isEqualTo("token_invalid");

        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 2) + "AA";
        assertThat(codeOf(authenticate("Bearer " + tampered))).isEqualTo("token_invalid");
    }

    @Test
    void stopsAcceptingATokenOnceItsUserIsDisabled() {
        User user = userRepository.findByCompanyIdAndUsername(acme.companyId(), "driver-1").orElseThrow();
        user.disable(Instant.now());
        userRepository.saveAndFlush(user);

        ResponseEntity<String> response = authenticate("Bearer " + token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(codeOf(response)).isEqualTo("user_disabled");
    }

    @Test
    void stopsAcceptingATokenOnceItsUserIsDeleted() {
        userRepository.deleteAllInBatch();

        ResponseEntity<String> response = authenticate("Bearer " + token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(codeOf(response)).isEqualTo("token_invalid");
    }

    @Test
    void isNotReachableOnTheGatewayFacingPort() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);

        ResponseEntity<String> response = rest.exchange("/api/v1/internal/authenticate", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> authenticate(String authorization) {
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return internalRest.exchange(internalUrl("/api/v1/internal/authenticate"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private static String codeOf(ResponseEntity<String> response) {
        return JsonPath.read(response.getBody(), "$.code");
    }
}
