package com.rls.gps.ping.location;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.jayway.jsonpath.JsonPath;
import com.rls.gps.common.web.GpsHeaders;
import com.rls.gps.ping.support.AbstractPingServiceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class LastLocationQueryIT extends AbstractPingServiceIT {

    private static final String COMPANY = "company-1";
    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private LastLocationRepository repository;

    @BeforeEach
    void seedLocations() {
        repository.save(COMPANY, "user-1", point(23.78, 90.40), NOW);
        repository.save(COMPANY, "user-2", point(23.79, 90.41), NOW);
        repository.save("company-2", "other-user", point(1.0, 1.0), NOW);
    }

    @Test
    void returnsTheLastLocationOfTheRequestedUsers() {
        ResponseEntity<String> response = query(COMPANY, "userIds=user-1,user-2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(JsonPath.<List<String>>read(body, "$.locations[*].userId"))
                .containsExactlyInAnyOrder("user-1", "user-2");
        assertThat(JsonPath.<List<Double>>read(body, "$.locations[?(@.userId=='user-1')].latitude"))
                .containsExactly(23.78);
        assertThat(JsonPath.<List<String>>read(body, "$.missing")).isEmpty();
    }

    @Test
    void reportsUsersThatHaveNoStoredLocation() {
        ResponseEntity<String> response = query(COMPANY, "userIds=user-1,never-reported");

        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.locations[*].userId"))
                .containsExactly("user-1");
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.missing"))
                .containsExactly("never-reported");
    }

    @Test
    void cannotReadAnotherCompanysUsers() {
        ResponseEntity<String> response = query(COMPANY, "userIds=other-user");

        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.locations")).isEmpty();
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.missing"))
                .containsExactly("other-user");
    }

    @Test
    void acceptsRepeatedParametersAsWellAsACommaSeparatedList() {
        ResponseEntity<String> response = query(COMPANY, "userIds=user-1&userIds=user-2");

        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.locations[*].userId"))
                .containsExactlyInAnyOrder("user-1", "user-2");
    }

    @Test
    void collapsesDuplicateUserIds() {
        ResponseEntity<String> response = query(COMPANY, "userIds=user-1,user-1,user-1");

        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.locations[*].userId"))
                .containsExactly("user-1");
    }

    @Test
    void refusesAnUnboundedFanOut() {
        String tooMany = IntStream.range(0, 150)
                .mapToObj(index -> "user-" + index)
                .collect(Collectors.joining(","));

        ResponseEntity<String> response = query(COMPANY, "userIds=" + tooMany);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("validation_failed");
    }

    @Test
    void requiresAtLeastOneUserId() {
        assertThat(query(COMPANY, "userIds=").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange("/api/v1/locations", HttpMethod.GET,
                new HttpEntity<>(identityHeaders(COMPANY)), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void requiresAVerifiedIdentity() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/locations?userIds=user-1", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("identity_required");
    }

    private ResponseEntity<String> query(String companyId, String query) {
        return rest.exchange("/api/v1/locations?" + query, HttpMethod.GET,
                new HttpEntity<>(identityHeaders(companyId)), String.class);
    }

    private static HttpHeaders identityHeaders(String companyId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(GpsHeaders.COMPANY_ID, companyId);
        headers.set(GpsHeaders.USER_ID, "requesting-user");
        return headers;
    }

    private static LocationPoint point(double latitude, double longitude) {
        return new LocationPoint(latitude, longitude, NOW.truncatedTo(ChronoUnit.MILLIS), null, null, null);
    }
}
