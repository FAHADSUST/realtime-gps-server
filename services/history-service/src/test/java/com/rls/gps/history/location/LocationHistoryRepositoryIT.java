package com.rls.gps.history.location;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.rls.gps.history.support.AbstractHistoryServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class LocationHistoryRepositoryIT extends AbstractHistoryServiceIT {

    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z").truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private LocationHistoryRepository repository;

    @Test
    void storesABatchOfFixes() {
        repository.insertAll(List.of(
                fix("company-1", "user-1", 23.78, 90.40, NOW),
                fix("company-1", "user-1", 23.79, 90.41, NOW.plusSeconds(60)),
                fix("company-1", "user-2", 23.70, 90.30, NOW)));

        assertThat(rowCount()).isEqualTo(3);
    }

    @Test
    void storesEveryFieldIncludingTheOptionalOnes() {
        repository.insertAll(List.of(new LocationFix("company-1", "user-1", 23.7808, 90.4019,
                NOW, NOW.plusSeconds(1), 5.0, 12.5, 180.0)));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM user_location");

        assertThat(row.get("company_id")).isEqualTo("company-1");
        assertThat(row.get("user_id")).isEqualTo("user-1");
        assertThat((Double) row.get("latitude")).isEqualTo(23.7808);
        assertThat((Double) row.get("longitude")).isEqualTo(90.4019);
        assertThat((Double) row.get("accuracy")).isEqualTo(5.0);
        assertThat((Double) row.get("speed")).isEqualTo(12.5);
        assertThat((Double) row.get("heading")).isEqualTo(180.0);
        assertThat(((LocalDateTime) row.get("recorded_at")).toInstant(ZoneOffset.UTC)).isEqualTo(NOW);
        assertThat(((LocalDateTime) row.get("received_at")).toInstant(ZoneOffset.UTC))
                .isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void keepsOptionalFieldsNullRatherThanZero() {
        repository.insertAll(List.of(fix("company-1", "user-1", 23.78, 90.40, NOW)));

        Map<String, Object> row = jdbc.queryForMap("SELECT accuracy, speed, heading FROM user_location");

        // Zero accuracy would claim a perfect fix; unknown must stay unknown.
        assertThat(row.get("accuracy")).isNull();
        assertThat(row.get("speed")).isNull();
        assertThat(row.get("heading")).isNull();
    }

    @Test
    void redeliveringTheSameBatchChangesNothing() {
        List<LocationFix> batch = List.of(
                fix("company-1", "user-1", 23.78, 90.40, NOW),
                fix("company-1", "user-1", 23.79, 90.41, NOW.plusSeconds(60)));

        repository.insertAll(batch);
        repository.insertAll(batch);
        repository.insertAll(batch);

        // What makes an at-least-once queue safe to consume.
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    void theSameInstantForDifferentUsersOrCompaniesIsNotADuplicate() {
        repository.insertAll(List.of(
                fix("company-1", "user-1", 1.0, 1.0, NOW),
                fix("company-1", "user-2", 2.0, 2.0, NOW),
                fix("company-2", "user-1", 3.0, 3.0, NOW)));

        assertThat(rowCount()).isEqualTo(3);
    }

    @Test
    void storesALargeBatchInOneGo() {
        List<LocationFix> batch = IntStream.range(0, 500)
                .mapToObj(index -> fix("company-1", "user-1", 23.0 + index / 10000.0, 90.0,
                        NOW.plusSeconds(index)))
                .toList();

        assertThat(repository.insertAll(batch)).isEqualTo(500);
        assertThat(rowCount()).isEqualTo(500);
    }

    @Test
    void anEmptyBatchTouchesNothing() {
        assertThat(repository.insertAll(List.of())).isZero();
        assertThat(rowCount()).isZero();
    }

    private static LocationFix fix(String companyId, String userId, double lat, double lon, Instant at) {
        return new LocationFix(companyId, userId, lat, lon, at, at.plusSeconds(1), null, null, null);
    }
}
