package com.rls.gps.ping.location;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

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

class NearbyUsersEndpointIT extends AbstractPingServiceIT {

    private static final String COMPANY = "company-1";
    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z").truncatedTo(ChronoUnit.MILLIS);

    private static final double SHAHBAGH_LAT = 23.7381;
    private static final double SHAHBAGH_LON = 90.3956;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private LastLocationRepository repository;

    @BeforeEach
    void seedUsers() {
        save(COMPANY, "at-shahbagh", SHAHBAGH_LAT, SHAHBAGH_LON);
        save(COMPANY, "in-dhanmondi", 23.7461, 90.3742);
        save(COMPANY, "in-gazipur", 23.9999, 90.4203);
        save("company-2", "other-company-neighbour", SHAHBAGH_LAT, SHAHBAGH_LON);
    }

    @Test
    void returnsNearbyUsersNearestFirstWithTheirDistance() {
        ResponseEntity<String> response = search(COMPANY, "lat=%f&lon=%f&radius=5"
                .formatted(SHAHBAGH_LAT, SHAHBAGH_LON));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(JsonPath.<List<String>>read(body, "$.users[*].userId"))
                .containsExactly("at-shahbagh", "in-dhanmondi");
        assertThat(JsonPath.<Integer>read(body, "$.count")).isEqualTo(2);
        assertThat(JsonPath.<Double>read(body, "$.users[1].distance")).isBetween(1.5, 3.5);
        assertThat(JsonPath.<String>read(body, "$.users[0].recordedAt")).isNotBlank();
    }

    @Test
    void echoesTheQueryBack() {
        String body = search(COMPANY, "lat=%f&lon=%f&radius=5&unit=KM"
                .formatted(SHAHBAGH_LAT, SHAHBAGH_LON)).getBody();

        assertThat(JsonPath.<Double>read(body, "$.radius")).isEqualTo(5.0);
        assertThat(JsonPath.<String>read(body, "$.unit")).isEqualTo("KM");
        assertThat(JsonPath.<Double>read(body, "$.latitude")).isCloseTo(SHAHBAGH_LAT,
                org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void neverReturnsAnotherCompanysUsers() {
        String body = search(COMPANY, "lat=%f&lon=%f&radius=50".formatted(SHAHBAGH_LAT, SHAHBAGH_LON))
                .getBody();

        assertThat(JsonPath.<List<String>>read(body, "$.users[*].userId"))
                .doesNotContain("other-company-neighbour");
    }

    @Test
    void honoursUnitAndLimit() {
        String metres = search(COMPANY, "lat=%f&lon=%f&radius=5000&unit=M"
                .formatted(SHAHBAGH_LAT, SHAHBAGH_LON)).getBody();
        assertThat(JsonPath.<Integer>read(metres, "$.count")).isEqualTo(2);

        String limited = search(COMPANY, "lat=%f&lon=%f&radius=50&limit=1"
                .formatted(SHAHBAGH_LAT, SHAHBAGH_LON)).getBody();
        assertThat(JsonPath.<Integer>read(limited, "$.count")).isEqualTo(1);
    }

    @Test
    void refusesARadiusLargerThanTheConfiguredCeiling() {
        ResponseEntity<String> response = search(COMPANY, "lat=%f&lon=%f&radius=5000"
                .formatted(SHAHBAGH_LAT, SHAHBAGH_LON));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("radius_too_large");
    }

    @Test
    void refusesTheSameOversizedRadiusWrittenInMetres() {
        ResponseEntity<String> response = search(COMPANY, "lat=%f&lon=%f&radius=5000000&unit=M"
                .formatted(SHAHBAGH_LAT, SHAHBAGH_LON));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("radius_too_large");
    }

    @Test
    void rejectsImpossibleCoordinatesAndNonsenseParameters() {
        assertThat(search(COMPANY, "lat=99&lon=90&radius=5").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(search(COMPANY, "lat=23&lon=90&radius=0").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(search(COMPANY, "lat=23&lon=90&radius=5&unit=PARSECS").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(search(COMPANY, "lon=90&radius=5").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void requiresAVerifiedIdentity() {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/v1/locations/users?lat=23&lon=90&radius=5", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("identity_required");
    }

    private ResponseEntity<String> search(String companyId, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(GpsHeaders.COMPANY_ID, companyId);
        headers.set(GpsHeaders.USER_ID, "requesting-user");

        return rest.exchange("/api/v1/locations/users?" + query, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    private void save(String companyId, String userId, double latitude, double longitude) {
        repository.save(companyId, userId,
                new LocationPoint(latitude, longitude, NOW, null, null, null), NOW);
    }
}
