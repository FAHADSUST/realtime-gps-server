package com.rls.gps.ping.location;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.rls.gps.common.web.GpsHeaders;
import com.rls.gps.ping.support.AbstractPingServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** POST /api/v1/locations behind the gateway, against a real Redis. */
class LocationIngestIT extends AbstractPingServiceIT {

    private static final String COMPANY = "company-1";
    private static final String USER = "user-1";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private LastLocationRepository repository;

    @Test
    void acceptsABatchAndStoresTheNewestFix() {
        Instant older = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant newest = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

        ResponseEntity<String> response = submit(COMPANY, USER, List.of(
                point(23.70, 90.40, older),
                point(23.78, 90.41, newest)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.accepted")).isEqualTo(2);
        assertThat(JsonPath.<Boolean>read(response.getBody(), "$.lastLocationUpdated")).isTrue();

        assertThat(repository.findByUserIds(COMPANY, List.of(USER)))
                .singleElement()
                .satisfies(location -> {
                    assertThat(location.latitude()).isEqualTo(23.78);
                    assertThat(location.recordedAt()).isEqualTo(newest);
                });
    }

    @Test
    void defaultsAMissingTimestampToNow() {
        ResponseEntity<String> response = rest.exchange("/api/v1/locations",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("locations",
                        List.of(Map.of("latitude", 23.78, "longitude", 90.41))), identityHeaders(COMPANY, USER)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(repository.findByUserIds(COMPANY, List.of(USER)))
                .singleElement()
                .satisfies(location -> assertThat(location.recordedAt()).isCloseTo(Instant.now(),
                        org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES)));
    }

    @Test
    void reportsWhenABatchWasEntirelyHistorical() {
        Instant recent = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        submit(COMPANY, USER, List.of(point(23.78, 90.41, recent)));

        ResponseEntity<String> response = submit(COMPANY, USER,
                List.of(point(1.0, 1.0, recent.minus(1, ChronoUnit.HOURS))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(JsonPath.<Boolean>read(response.getBody(), "$.lastLocationUpdated")).isFalse();
        assertThat(repository.findByUserIds(COMPANY, List.of(USER)))
                .singleElement()
                .satisfies(location -> assertThat(location.latitude()).isEqualTo(23.78));
    }

    @Test
    void rejectsATimestampFarInTheFuture() {
        ResponseEntity<String> response = submit(COMPANY, USER,
                List.of(point(23.78, 90.41, Instant.now().plus(2, ChronoUnit.HOURS))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code"))
                .isEqualTo("location_timestamp_in_future");
        assertThat(repository.findByUserIds(COMPANY, List.of(USER))).isEmpty();
    }

    @Test
    void rejectsImpossibleCoordinates() {
        ResponseEntity<String> response = submit(COMPANY, USER,
                List.of(point(99.0, 200.0, Instant.now())));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("validation_failed");
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.errors")).hasSize(2);
    }

    @Test
    void rejectsAMissingCoordinateRatherThanDefaultingItToZero() {
        ResponseEntity<String> response = rest.exchange("/api/v1/locations",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("locations", List.of(Map.of("longitude", 90.41))),
                        identityHeaders(COMPANY, USER)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findByUserIds(COMPANY, List.of(USER))).isEmpty();
    }

    @Test
    void rejectsAnEmptyBatch() {
        ResponseEntity<String> response = submit(COMPANY, USER, List.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void requiresAVerifiedIdentity() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = rest.exchange("/api/v1/locations",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("locations", List.of(point(1.0, 1.0, Instant.now()))), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("identity_required");
    }

    @Test
    void storesUnderTheVerifiedUserNotAnythingInThePayload() {
        // The payload has no user field at all; this proves the scoping comes from the headers.
        submit(COMPANY, "user-a", List.of(point(1.0, 1.0, Instant.now())));
        submit(COMPANY, "user-b", List.of(point(2.0, 2.0, Instant.now())));

        assertThat(repository.findByUserIds(COMPANY, List.of("user-a")))
                .singleElement()
                .satisfies(location -> assertThat(location.latitude()).isEqualTo(1.0));
        assertThat(repository.findByUserIds(COMPANY, List.of("user-b")))
                .singleElement()
                .satisfies(location -> assertThat(location.latitude()).isEqualTo(2.0));
    }

    private ResponseEntity<String> submit(String companyId, String userId, List<Map<String, Object>> points) {
        return rest.exchange("/api/v1/locations", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("locations", points), identityHeaders(companyId, userId)),
                String.class);
    }

    private static Map<String, Object> point(double latitude, double longitude, Instant recordedAt) {
        return Map.of("latitude", latitude, "longitude", longitude, "recordedAt", recordedAt.toString());
    }

    private static HttpHeaders identityHeaders(String companyId, String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(GpsHeaders.COMPANY_ID, companyId);
        headers.set(GpsHeaders.USER_ID, userId);
        return headers;
    }
}
