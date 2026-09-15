package com.rls.gps.history.consume;

import java.time.Instant;

import com.rls.gps.messaging.LocationMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixValidationTest {

    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");

    @Test
    void acceptsAWellFormedFix() {
        assertThat(FixValidation.rejectionReason(fix("company-1", "user-1", 23.78, 90.40))).isNull();
    }

    @Test
    void acceptsTheExtremesOfTheCoordinateSystem() {
        assertThat(FixValidation.rejectionReason(fix("c", "u", 90, 180))).isNull();
        assertThat(FixValidation.rejectionReason(fix("c", "u", -90, -180))).isNull();
        assertThat(FixValidation.rejectionReason(fix("c", "u", 0, 0))).isNull();
    }

    @Test
    void rejectsAFixWithNoOwner() {
        assertThat(FixValidation.rejectionReason(fix(null, "user-1", 1, 1)))
                .isEqualTo("missing company or user");
        assertThat(FixValidation.rejectionReason(fix("company-1", "  ", 1, 1)))
                .isEqualTo("missing company or user");
    }

    @Test
    void rejectsAFixWithNoTime() {
        LocationMessage noRecordedAt = new LocationMessage("c", "u", 1, 1, null, NOW, null, null, null);

        assertThat(FixValidation.rejectionReason(noRecordedAt)).isEqualTo("missing timestamp");
    }

    @Test
    void rejectsCoordinatesThatAreNotNumbers() {
        assertThat(FixValidation.rejectionReason(fix("c", "u", Double.NaN, 1)))
                .isEqualTo("coordinate is not a number");
        assertThat(FixValidation.rejectionReason(fix("c", "u", 1, Double.POSITIVE_INFINITY)))
                .isEqualTo("coordinate is not a number");
    }

    @Test
    void rejectsCoordinatesOffTheGlobe() {
        assertThat(FixValidation.rejectionReason(fix("c", "u", 91, 0))).isEqualTo("coordinate out of range");
        assertThat(FixValidation.rejectionReason(fix("c", "u", 0, 181))).isEqualTo("coordinate out of range");
    }

    @Test
    void rejectsNothingAtAll() {
        assertThat(FixValidation.rejectionReason(null)).isEqualTo("null fix");
    }

    private static LocationMessage fix(String companyId, String userId, double lat, double lon) {
        return new LocationMessage(companyId, userId, lat, lon, NOW, NOW, null, null, null);
    }
}
