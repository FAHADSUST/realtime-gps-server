package com.rls.gps.ping.location;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.rls.gps.ping.support.AbstractPingServiceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Distances are real ones around Dhaka, so the assertions mean something: Shahbagh to Dhanmondi is
 * roughly 2.5 km, and Gazipur is far outside any city-scale radius.
 */
class RadiusSearchIT extends AbstractPingServiceIT {

    private static final String COMPANY = "company-1";
    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z").truncatedTo(ChronoUnit.MILLIS);

    private static final double SHAHBAGH_LAT = 23.7381;
    private static final double SHAHBAGH_LON = 90.3956;

    @Autowired
    private LastLocationRepository repository;

    @BeforeEach
    void seedUsers() {
        save("at-shahbagh", SHAHBAGH_LAT, SHAHBAGH_LON);
        save("in-dhanmondi", 23.7461, 90.3742);   // ~2 km away
        save("in-gulshan", 23.7925, 90.4078);     // ~6 km away
        save("in-gazipur", 23.9999, 90.4203);     // ~29 km away
        save("other-company-neighbour", SHAHBAGH_LAT, SHAHBAGH_LON, "company-2");
    }

    @Test
    void findsUsersInsideTheRadiusNearestFirst() {
        List<NearbyUser> nearby = search(5, RadiusUnit.KM, 10);

        assertThat(nearby).extracting(user -> user.location().userId())
                .containsExactly("at-shahbagh", "in-dhanmondi");
        assertThat(nearby.get(0).distance()).isCloseTo(0.0, within(0.1));
        assertThat(nearby.get(1).distance()).isCloseTo(2.3, within(0.5));
    }

    @Test
    void widensAndNarrowsWithTheRadius() {
        assertThat(search(1, RadiusUnit.KM, 10)).hasSize(1);
        assertThat(search(10, RadiusUnit.KM, 10)).hasSize(3);
        assertThat(search(50, RadiusUnit.KM, 10)).hasSize(4);
    }

    @Test
    void acceptsOtherUnits() {
        assertThat(search(5000, RadiusUnit.M, 10)).hasSize(2);
        assertThat(search(3, RadiusUnit.MI, 10)).hasSize(2);
    }

    @Test
    void neverCrossesACompanyBoundary() {
        assertThat(search(50, RadiusUnit.KM, 10))
                .extracting(user -> user.location().userId())
                .doesNotContain("other-company-neighbour");

        assertThat(repository.findWithinRadius("company-2", SHAHBAGH_LAT, SHAHBAGH_LON, 1,
                RadiusUnit.KM, 10))
                .extracting(user -> user.location().userId())
                .containsExactly("other-company-neighbour");
    }

    @Test
    void honoursTheLimit() {
        List<NearbyUser> nearby = search(50, RadiusUnit.KM, 2);

        assertThat(nearby).hasSize(2);
        assertThat(nearby).extracting(user -> user.location().userId())
                .containsExactly("at-shahbagh", "in-dhanmondi");
    }

    @Test
    void returnsTheStoredPositionNotJustAnId() {
        NearbyUser nearest = search(1, RadiusUnit.KM, 10).get(0);

        assertThat(nearest.location().latitude()).isCloseTo(SHAHBAGH_LAT, within(0.0001));
        assertThat(nearest.location().longitude()).isCloseTo(SHAHBAGH_LON, within(0.0001));
        assertThat(nearest.location().recordedAt()).isEqualTo(NOW);
    }

    @Test
    void ignoresAndForgetsMembersWhoseLocationExpired() {
        // Exactly the case the geo index cannot express on its own: the member is still indexed,
        // but its location key is gone.
        redis.delete(repository.lastLocationKey(COMPANY, "in-dhanmondi"));

        List<NearbyUser> nearby = search(5, RadiusUnit.KM, 10);

        assertThat(nearby).extracting(user -> user.location().userId()).containsExactly("at-shahbagh");
        assertThat(redis.opsForZSet().score(repository.geoKey(COMPANY), "in-dhanmondi"))
                .as("a member with no location must be dropped from the index, not left to rot")
                .isNull();
    }

    @Test
    void returnsNothingWhenNobodyIsNearbyOrNothingIsIndexed() {
        assertThat(repository.findWithinRadius(COMPANY, -33.8688, 151.2093, 5, RadiusUnit.KM, 10))
                .isEmpty();
        assertThat(repository.findWithinRadius("company-with-no-users", SHAHBAGH_LAT, SHAHBAGH_LON,
                5, RadiusUnit.KM, 10)).isEmpty();
    }

    private List<NearbyUser> search(double radius, RadiusUnit unit, int limit) {
        return repository.findWithinRadius(COMPANY, SHAHBAGH_LAT, SHAHBAGH_LON, radius, unit, limit);
    }

    private void save(String userId, double latitude, double longitude) {
        save(userId, latitude, longitude, COMPANY);
    }

    private void save(String userId, double latitude, double longitude, String companyId) {
        repository.save(companyId, userId,
                new LocationPoint(latitude, longitude, NOW, null, null, null), NOW);
    }
}
