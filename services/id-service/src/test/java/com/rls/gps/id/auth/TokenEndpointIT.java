package com.rls.gps.id.auth;

import java.time.Instant;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.rls.gps.id.support.AbstractIdServiceIT;
import com.rls.gps.id.token.TokenService;
import com.rls.gps.id.token.VerifiedToken;
import com.rls.gps.id.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEndpointIT extends AbstractIdServiceIT {

    private static final String PASSWORD = "s3cret-password";

    @Autowired
    private TokenService tokenService;

    private RegisteredCompany acme;
    private RegisteredCompany other;
    private String driverId;

    @BeforeEach
    void seed() {
        acme = registerCompany("Acme Logistics");
        other = registerCompany("Other Corp");
        driverId = registerUser(acme, "driver-1", PASSWORD);
    }

    @Test
    void issuesATokenCarryingTheCallerIdentity() {
        ResponseEntity<String> response = requestToken(acme.appKey(), "driver-1", PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(JsonPath.<String>read(body, "$.tokenType")).isEqualTo("Bearer");
        assertThat(JsonPath.<Integer>read(body, "$.expiresIn")).isEqualTo(3600);
        assertThat(JsonPath.<String>read(body, "$.companyId")).isEqualTo(acme.companyId());
        assertThat(JsonPath.<String>read(body, "$.userId")).isEqualTo(driverId);

        VerifiedToken verified = tokenService.verify(JsonPath.read(body, "$.accessToken"));
        assertThat(verified.companyId()).isEqualTo(acme.companyId());
        assertThat(verified.userId()).isEqualTo(driverId);
        assertThat(verified.appKey()).isEqualTo(acme.appKey());
        assertThat(verified.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void rejectsAWrongPassword() {
        ResponseEntity<String> response = requestToken(acme.appKey(), "driver-1", "wrong-password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("invalid_credentials");
    }

    @Test
    void unknownUserUnknownAppKeyAndWrongPasswordAreIndistinguishable() {
        String wrongPassword = codeOf(requestToken(acme.appKey(), "driver-1", "wrong-password"));
        String unknownUser = codeOf(requestToken(acme.appKey(), "nobody", PASSWORD));
        String unknownAppKey = codeOf(requestToken("ak_does-not-exist", "driver-1", PASSWORD));

        assertThat(unknownUser).isEqualTo(wrongPassword);
        assertThat(unknownAppKey).isEqualTo(wrongPassword);
    }

    @Test
    void aUserCannotLogInThroughAnotherCompanysAppKey() {
        ResponseEntity<String> response = requestToken(other.appKey(), "driver-1", PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("invalid_credentials");
    }

    @Test
    void refusesADisabledUser() {
        User user = userRepository.findByCompanyIdAndUsername(acme.companyId(), "driver-1").orElseThrow();
        user.disable(Instant.now());
        userRepository.saveAndFlush(user);

        ResponseEntity<String> response = requestToken(acme.appKey(), "driver-1", PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("user_disabled");
    }

    @Test
    void rejectsAnIncompleteRequest() {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/auth/token",
                Map.of("appKey", acme.appKey()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("validation_failed");
    }

    @Test
    void neverEchoesThePassword() {
        String body = requestToken(acme.appKey(), "driver-1", "wrong-password").getBody();

        assertThat(body).doesNotContain("wrong-password");
    }

    @Test
    void tokenIssuanceIsNotServedOnTheRestrictedPort() {
        ResponseEntity<String> response = internalRest.postForEntity(internalUrl("/api/v1/auth/token"),
                Map.of("appKey", acme.appKey(), "username", "driver-1", "password", PASSWORD), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> requestToken(String appKey, String username, String password) {
        return rest.postForEntity("/api/v1/auth/token",
                Map.of("appKey", appKey, "username", username, "password", password), String.class);
    }

    private static String codeOf(ResponseEntity<String> response) {
        return JsonPath.read(response.getBody(), "$.code");
    }
}
