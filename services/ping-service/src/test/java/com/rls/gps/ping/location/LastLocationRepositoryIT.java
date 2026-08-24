package com.rls.gps.ping.location;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.rls.gps.ping.support.AbstractPingServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class LastLocationRepositoryIT extends AbstractPingServiceIT {

    private static final String COMPANY = "company-1";
    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    @Autowired
    private LastLocationRepository repository;

    @Test
    void storesAndReadsBackALocation() {
        LocationPoint point = point(23.7808, 90.4019, NOW, 5.0, 12.5, 180.0);

        assertThat(repository.save(COMPANY, "user-1", point, NOW)).isTrue();

        List<LastLocation> found = repository.findByUserIds(COMPANY, List.of("user-1"));
        assertThat(found).hasSize(1);
        LastLocation location = found.get(0);
        assertThat(location.userId()).isEqualTo("user-1");
        assertThat(location.latitude()).isEqualTo(23.7808);
        assertThat(location.longitude()).isEqualTo(90.4019);
        assertThat(location.recordedAt()).isEqualTo(NOW);
        assertThat(location.accuracy()).isEqualTo(5.0);
        assertThat(location.speed()).isEqualTo(12.5);
        assertThat(location.heading()).isEqualTo(180.0);
    }

    @Test
    void keepsOnlyTheLatestFix() {
        repository.save(COMPANY, "user-1", point(1.0, 1.0, NOW), NOW);
        Instant later = NOW.plus(Duration.ofMinutes(5));

        assertThat(repository.save(COMPANY, "user-1", point(2.0, 2.0, later), later)).isTrue();

        assertThat(repository.findByUserIds(COMPANY, List.of("user-1")))
                .singleElement()
                .satisfies(location -> {
                    assertThat(location.latitude()).isEqualTo(2.0);
                    assertThat(location.recordedAt()).isEqualTo(later);
                });
    }

    @Test
    void refusesToLetAnOlderFixOverwriteANewerOne() {
        Instant later = NOW.plus(Duration.ofMinutes(5));
        repository.save(COMPANY, "user-1", point(2.0, 2.0, later), later);

        // A device flushing an offline buffer: old points, arriving now.
        boolean stored = repository.save(COMPANY, "user-1", point(1.0, 1.0, NOW), later);

        assertThat(stored).as("an older fix must not become the last known location").isFalse();
        assertThat(repository.findByUserIds(COMPANY, List.of("user-1")))
                .singleElement()
                .satisfies(location -> assertThat(location.recordedAt()).isEqualTo(later));
    }

    @Test
    void treatsAnIdenticalTimestampAsAlreadyStored() {
        repository.save(COMPANY, "user-1", point(1.0, 1.0, NOW), NOW);

        assertThat(repository.save(COMPANY, "user-1", point(9.0, 9.0, NOW), NOW)).isFalse();
    }

    @Test
    void keepsCompaniesApart() {
        repository.save(COMPANY, "user-1", point(1.0, 1.0, NOW), NOW);
        repository.save("company-2", "user-1", point(2.0, 2.0, NOW), NOW);

        assertThat(repository.findByUserIds(COMPANY, List.of("user-1")))
                .singleElement()
                .satisfies(location -> assertThat(location.latitude()).isEqualTo(1.0));
        assertThat(repository.findByUserIds("company-2", List.of("user-1")))
                .singleElement()
                .satisfies(location -> assertThat(location.latitude()).isEqualTo(2.0));
    }

    @Test
    void readsManyUsersAtOnceAndSkipsThoseWithNothingStored() {
        repository.save(COMPANY, "user-1", point(1.0, 1.0, NOW), NOW);
        repository.save(COMPANY, "user-3", point(3.0, 3.0, NOW), NOW);

        List<LastLocation> found =
                repository.findByUserIds(COMPANY, List.of("user-1", "user-2", "user-3"));

        assertThat(found).extracting(LastLocation::userId).containsExactly("user-1", "user-3");
    }

    @Test
    void addsTheUserToTheCompanyGeoIndex() {
        repository.save(COMPANY, "user-1", point(23.7808, 90.4019, NOW), NOW);

        assertThat(redis.opsForZSet().size(repository.geoKey(COMPANY))).isEqualTo(1);
        assertThat(redis.opsForGeo().position(repository.geoKey(COMPANY), "user-1"))
                .singleElement()
                .satisfies(position -> {
                    // Redis stores geohashes, so a fix comes back close rather than exact.
                    assertThat(position.getX()).isCloseTo(90.4019, org.assertj.core.data.Offset.offset(0.0001));
                    assertThat(position.getY()).isCloseTo(23.7808, org.assertj.core.data.Offset.offset(0.0001));
                });
    }

    @Test
    void givesTheLocationKeyALifetime() {
        repository.save(COMPANY, "user-1", point(1.0, 1.0, NOW), NOW);

        Long ttl = redis.getExpire(repository.lastLocationKey(COMPANY, "user-1"));

        assertThat(ttl).as("a silent user must eventually stop answering queries").isPositive();
    }

    @Test
    void returnsNothingForAnEmptyRequest() {
        assertThat(repository.findByUserIds(COMPANY, List.of())).isEmpty();
    }

    private static LocationPoint point(double lat, double lon, Instant recordedAt) {
        return point(lat, lon, recordedAt, null, null, null);
    }

    private static LocationPoint point(double lat, double lon, Instant recordedAt,
                                       Double accuracy, Double speed, Double heading) {
        return new LocationPoint(lat, lon, recordedAt, accuracy, speed, heading);
    }
}
