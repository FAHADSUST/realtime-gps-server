package com.rls.gps.ping.publish;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import com.rls.gps.messaging.LocationMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocationBufferTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final Duration SHORT_INTERVAL = Duration.ofMillis(50);

    @Test
    void returnsWhatWasOffered() throws Exception {
        LocationBuffer buffer = new LocationBuffer(10, 5, SHORT_INTERVAL);

        assertThat(buffer.offer(message("user-1"))).isTrue();

        assertThat(buffer.takeBatch()).extracting(LocationMessage::userId).containsExactly("user-1");
    }

    @Test
    void batchesEverythingAvailableUpToTheBatchSize() throws Exception {
        LocationBuffer buffer = new LocationBuffer(100, 3, SHORT_INTERVAL);
        IntStream.range(0, 7).forEach(index -> buffer.offer(message("user-" + index)));

        assertThat(buffer.takeBatch()).hasSize(3);
        assertThat(buffer.takeBatch()).hasSize(3);
        assertThat(buffer.takeBatch()).hasSize(1);
    }

    @Test
    void returnsNothingWhenTheIntervalPassesWithNoWork() throws Exception {
        LocationBuffer buffer = new LocationBuffer(10, 5, SHORT_INTERVAL);

        long start = System.nanoTime();
        List<LocationMessage> batch = buffer.takeBatch();
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(batch).isEmpty();
        assertThat(waited).as("must wait for work rather than spin").isGreaterThanOrEqualTo(SHORT_INTERVAL);
    }

    @Test
    void doesNotWaitForTheIntervalWhenWorkIsAlreadyThere() throws Exception {
        LocationBuffer buffer = new LocationBuffer(10, 5, Duration.ofSeconds(30));
        buffer.offer(message("user-1"));

        long start = System.nanoTime();
        buffer.takeBatch();
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(waited).as("a full buffer must not wait for the flush interval")
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void refusesWorkOnceFullRatherThanGrowing() {
        LocationBuffer buffer = new LocationBuffer(2, 10, SHORT_INTERVAL);

        assertThat(buffer.offer(message("user-1"))).isTrue();
        assertThat(buffer.offer(message("user-2"))).isTrue();
        assertThat(buffer.offer(message("user-3"))).as("bounded: the third must be refused").isFalse();

        assertThat(buffer.size()).isEqualTo(2);
        assertThat(buffer.remainingCapacity()).isZero();
    }

    @Test
    void reportsItsOwnOccupancy() {
        LocationBuffer buffer = new LocationBuffer(10, 10, SHORT_INTERVAL);

        assertThat(buffer.size()).isZero();
        buffer.offer(message("user-1"));
        assertThat(buffer.size()).isEqualTo(1);
        assertThat(buffer.remainingCapacity()).isEqualTo(9);
    }

    @Test
    void drainsWhatIsLeftInBatchSizedChunks() {
        LocationBuffer buffer = new LocationBuffer(100, 2, SHORT_INTERVAL);
        IntStream.range(0, 5).forEach(index -> buffer.offer(message("user-" + index)));

        List<List<LocationMessage>> remaining = buffer.drainRemaining();

        assertThat(remaining).hasSize(3);
        assertThat(remaining.stream().mapToInt(List::size).sum()).isEqualTo(5);
        assertThat(remaining.get(0)).hasSize(2);
        assertThat(remaining.get(2)).hasSize(1);
        assertThat(buffer.size()).isZero();
    }

    @Test
    void drainingAnEmptyBufferYieldsNothing() {
        assertThat(new LocationBuffer(10, 5, SHORT_INTERVAL).drainRemaining()).isEmpty();
    }

    private static LocationMessage message(String userId) {
        return new LocationMessage("company-1", userId, 23.78, 90.40, NOW, NOW, null, null, null);
    }
}
