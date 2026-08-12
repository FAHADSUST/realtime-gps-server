package com.rls.gps.id.user;

import java.util.List;

import com.jayway.jsonpath.JsonPath;
import com.rls.gps.common.web.GpsHeaders;
import com.rls.gps.id.support.AbstractIdServiceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolve runs behind the gateway, so these tests send the identity headers Kong would inject.
 */
class UserResolveIT extends AbstractIdServiceIT {

    private static final String PASSWORD = "s3cret-password";

    private RegisteredCompany acme;
    private RegisteredCompany other;

    @BeforeEach
    void seed() {
        acme = registerCompany("Acme Logistics");
        other = registerCompany("Other Corp");

        registerUser(acme, "charlie", PASSWORD);
        registerUser(acme, "alice", PASSWORD);
        registerUser(acme, "bob", PASSWORD);
        registerUser(other, "zoe", PASSWORD);
    }

    @Test
    void listsOnlyTheCallersCompanyUsersOrderedByUsername() {
        ResponseEntity<String> response = resolve(acme, "alice", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(JsonPath.<List<String>>read(body, "$.users[*].username"))
                .containsExactly("alice", "bob", "charlie");
        assertThat(JsonPath.<Integer>read(body, "$.totalElements")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(body, "$.totalPages")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(body, "$.users[*].companyId"))
                .containsOnly(acme.companyId());
    }

    @Test
    void anotherCompanySeesOnlyItsOwnUsers() {
        ResponseEntity<String> response = resolve(other, "zoe", "");

        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.users[*].username"))
                .containsExactly("zoe");
    }

    @Test
    void pagesThroughTheResults() {
        String firstPage = resolve(acme, "alice", "?page=0&size=2").getBody();
        assertThat(JsonPath.<List<String>>read(firstPage, "$.users[*].username"))
                .containsExactly("alice", "bob");
        assertThat(JsonPath.<Integer>read(firstPage, "$.totalPages")).isEqualTo(2);

        String secondPage = resolve(acme, "alice", "?page=1&size=2").getBody();
        assertThat(JsonPath.<List<String>>read(secondPage, "$.users[*].username"))
                .containsExactly("charlie");

        String beyondTheEnd = resolve(acme, "alice", "?page=9&size=2").getBody();
        assertThat(JsonPath.<List<String>>read(beyondTheEnd, "$.users[*].username")).isEmpty();
        assertThat(JsonPath.<Integer>read(beyondTheEnd, "$.totalElements")).isEqualTo(3);
    }

    @Test
    void neverExposesPasswordHashes() {
        assertThat(resolve(acme, "alice", "").getBody())
                .doesNotContain("passwordHash").doesNotContain("$2");
    }

    @Test
    void acceptsAMatchingAppKeyAndRefusesAnotherCompanys() {
        assertThat(resolve(acme, "alice", "?appKey=" + acme.appKey()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> crossTenant = resolve(acme, "alice", "?appKey=" + other.appKey());

        assertThat(crossTenant.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(JsonPath.<String>read(crossTenant.getBody(), "$.code")).isEqualTo("app_key_mismatch");
    }

    @Test
    void requiresAVerifiedIdentity() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/user/resolve", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("identity_required");
    }

    @Test
    void rejectsAnOversizedPage() {
        ResponseEntity<String> response = resolve(acme, "alice", "?size=1000");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("validation_failed");
    }

    private ResponseEntity<String> resolve(RegisteredCompany company, String username, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(GpsHeaders.COMPANY_ID, company.companyId());
        headers.set(GpsHeaders.USER_ID, userRepository
                .findByCompanyIdAndUsername(company.companyId(), username).orElseThrow().getId());
        headers.set(GpsHeaders.APP_KEY, company.appKey());

        return rest.exchange("/api/v1/user/resolve" + query, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }
}
