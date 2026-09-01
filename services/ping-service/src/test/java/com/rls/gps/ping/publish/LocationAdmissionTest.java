package com.rls.gps.ping.publish;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import com.rls.gps.common.error.ApiException;
import com.rls.gps.messaging.LocationMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocationAdmissionTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    @Test
    void admitsEverythingWhileThereIsRoom() {
        LocationBuffer buffer = buffer(10);
        LocationAdmission admission = new LocationAdmission(buffer, OverflowPolicy.DROP_NEWEST);

        assertThat(admission.admit(messages(5))).isZero();
        assertThat(buffer.size()).isEqualTo(5);
    }

    @Test
    void dropNewestKeepsWhatWasAlreadyAccepted() {
        LocationBuffer buffer = buffer(3);
        LocationAdmission admission = new LocationAdmission(buffer, OverflowPolicy.DROP_NEWEST);

        int dropped = admission.admit(messages(5));

        assertThat(dropped).isEqualTo(2);
        assertThat(buffer.size()).isEqualTo(3);
        // The first three were already answered with a 202; they are the ones kept.
        assertThat(buffer.drainRemaining().get(0)).extracting(LocationMessage::userId)
                .containsExactly("user-0", "user-1", "user-2");
    }

    @Test
    void dropOldestFavoursTheMostRecentTrack() {
        LocationBuffer buffer = buffer(3);
        LocationAdmission admission = new LocationAdmission(buffer, OverflowPolicy.DROP_OLDEST);

        int dropped = admission.admit(messages(5));

        assertThat(dropped).isZero();
        assertThat(buffer.size()).isEqualTo(3);
        assertThat(buffer.drainRemaining().get(0)).extracting(LocationMessage::userId)
                .containsExactly("user-2", "user-3", "user-4");
    }

    @Test
    void rejectFailsTheRequestSoAClientCanRetry() {
        LocationBuffer buffer = buffer(2);
        LocationAdmission admission = new LocationAdmission(buffer, OverflowPolicy.REJECT);

        assertThatThrownBy(() -> admission.admit(messages(5)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(ex.code()).isEqualTo("location_buffer_full");
                });
    }

    @Test
    void rejectStaysQuietWhileThereIsRoom() {
        LocationAdmission admission = new LocationAdmission(buffer(10), OverflowPolicy.REJECT);

        assertThatCode(() -> admission.admit(messages(10))).doesNotThrowAnyException();
    }

    private static LocationBuffer buffer(int capacity) {
        return new LocationBuffer(capacity, capacity, Duration.ofMillis(50));
    }

    private static List<LocationMessage> messages(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> new LocationMessage("company-1", "user-" + index,
                        23.78, 90.40, NOW, NOW, null, null, null))
                .toList();
    }
}
