package com.rls.gps.id.user;

import java.util.HashMap;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.rls.gps.id.support.AbstractIdServiceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class UserSignupIT extends AbstractIdServiceIT {

    @Autowired
    private PasswordEncoder passwordEncoder;

    private RegisteredCompany acme;
    private RegisteredCompany other;

    @BeforeEach
    void registerCompanies() {
        acme = registerCompany("Acme Logistics");
        other = registerCompany("Other Corp");
    }

    @Test
    void registersAUserForTheCompanyOwningTheAppKey() {
        ResponseEntity<String> response = signup(acme, "driver-1", "s3cret-password", "Driver One");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String body = response.getBody();
        assertThat(JsonPath.<String>read(body, "$.userId")).isNotBlank();
        assertThat(JsonPath.<String>read(body, "$.companyId")).isEqualTo(acme.companyId());
        assertThat(JsonPath.<String>read(body, "$.username")).isEqualTo("driver-1");
        assertThat(JsonPath.<String>read(body, "$.displayName")).isEqualTo("Driver One");
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("ACTIVE");

        User saved = userRepository.findByCompanyIdAndUsername(acme.companyId(), "driver-1").orElseThrow();
        assertThat(passwordEncoder.matches("s3cret-password", saved.getPasswordHash())).isTrue();
    }

    @Test
    void neverReturnsThePasswordOrItsHash() {
        String body = signup(acme, "driver-1", "s3cret-password", null).getBody();

        assertThat(body).doesNotContain("s3cret-password").doesNotContain("passwordHash").doesNotContain("$2");
    }

    @Test
    void rejectsAWrongAppSecret() {
        ResponseEntity<String> response = signupWith(acme.appKey(), "as_not-the-secret",
                "driver-1", "s3cret-password", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("invalid_company_credentials");
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void anUnknownAppKeyIsIndistinguishableFromAWrongSecret() {
        ResponseEntity<String> unknownKey = signupWith("ak_does-not-exist", acme.appSecret(),
                "driver-1", "s3cret-password", null);
        ResponseEntity<String> wrongSecret = signupWith(acme.appKey(), "as_wrong",
                "driver-1", "s3cret-password", null);

        assertThat(unknownKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(JsonPath.<String>read(unknownKey.getBody(), "$.code"))
                .isEqualTo(JsonPath.<String>read(wrongSecret.getBody(), "$.code"));
    }

    @Test
    void rejectsADuplicateUsernameWithinTheSameCompany() {
        assertThat(signup(acme, "driver-1", "s3cret-password", null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> duplicate = signup(acme, "driver-1", "another-password", null);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(JsonPath.<String>read(duplicate.getBody(), "$.code")).isEqualTo("user_already_exists");
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void theSameUsernameIsFineInAnotherCompany() {
        assertThat(signup(acme, "driver-1", "s3cret-password", null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(signup(other, "driver-1", "s3cret-password", null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(userRepository.count()).isEqualTo(2);
    }

    @Test
    void rejectsAShortPasswordAndAnIllegalUsername() {
        ResponseEntity<String> response = signup(acme, "bad name!", "short", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("validation_failed");
        assertThat(JsonPath.<java.util.List<String>>read(response.getBody(), "$.errors")).hasSize(2);
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void signupIsNotServedOnTheRestrictedPort() {
        Map<String, String> body = bodyFor(acme.appKey(), acme.appSecret(), "driver-1", "s3cret-password", null);

        ResponseEntity<String> response = internalRest.postForEntity(
                internalUrl("/api/v1/user/signup"), body, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> signup(RegisteredCompany company, String username, String password,
                                          String displayName) {
        return signupWith(company.appKey(), company.appSecret(), username, password, displayName);
    }

    private ResponseEntity<String> signupWith(String appKey, String appSecret, String username,
                                              String password, String displayName) {
        return rest.postForEntity("/api/v1/user/signup",
                bodyFor(appKey, appSecret, username, password, displayName), String.class);
    }

    private static Map<String, String> bodyFor(String appKey, String appSecret, String username,
                                               String password, String displayName) {
        Map<String, String> body = new HashMap<>();
        body.put("appKey", appKey);
        body.put("appSecret", appSecret);
        body.put("username", username);
        body.put("password", password);
        if (displayName != null) {
            body.put("displayName", displayName);
        }
        return body;
    }
}
