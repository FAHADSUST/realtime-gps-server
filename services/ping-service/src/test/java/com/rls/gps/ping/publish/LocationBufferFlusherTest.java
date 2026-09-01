package com.rls.gps.ping.publish;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import com.rls.gps.messaging.LocationMessage;
import com.rls.gps.ping.metrics.PingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class LocationBufferFlusherTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    private final CollectingSink sink = new CollectingSink();

    @Test
    void publishesWhatIsBuffered() {
        LocationBuffer buffer = new LocationBuffer(100, 10, Duration.ofMillis(50));
        LocationBufferFlusher flusher = start(buffer);

        try {
            buffer.offer(message("user-1"));
            buffer.offer(message("user-2"));

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(sink.received()).hasSize(2));
        } finally {
            flusher.stop();
        }
    }

    @Test
    void publishesInBatchesRatherThanOneMessagePerFix() {
        LocationBuffer buffer = new LocationBuffer(1000, 100, Duration.ofMillis(200));
        IntStream.range(0, 100).forEach(index -> buffer.offer(message("user-" + index)));

        LocationBufferFlusher flusher = start(buffer);
        try {
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(sink.received()).hasSize(100));

            assertThat(sink.batchCount())
                    .as("100 fixes should take a handful of batches, not 100")
                    .isLessThanOrEqualTo(3);
        } finally {
            flusher.stop();
        }
    }

    @Test
    void drainsTheBufferOnShutdownInsteadOfLosingIt() {
        // A long interval means the loop is parked waiting: nothing would be published without the
        // explicit drain during stop().
        LocationBuffer buffer = new LocationBuffer(100, 10, Duration.ofMillis(50));
        LocationBufferFlusher flusher = start(buffer);
        IntStream.range(0, 25).forEach(index -> buffer.offer(message("user-" + index)));

        flusher.stop();

        assertThat(sink.received()).as("everything buffered at shutdown must still be published")
                .hasSize(25);
        assertThat(buffer.size()).isZero();
        assertThat(flusher.isRunning()).isFalse();
    }

    @Test
    void keepsRunningWhenTheSinkFails() {
        FailingThenWorkingSink flaky = new FailingThenWorkingSink();
        LocationBuffer buffer = new LocationBuffer(100, 1, Duration.ofMillis(50));
        LocationBufferFlusher flusher = new LocationBufferFlusher(buffer, flaky, metrics(), 2000);
        flusher.start();

        try {
            buffer.offer(message("doomed"));
            await().atMost(Duration.ofSeconds(5)).until(() -> flaky.attempts.get() >= 1);

            // The broker recovers; the flusher must still be alive to notice.
            buffer.offer(message("later"));

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(flaky.delivered).extracting(LocationMessage::userId)
                            .contains("later"));
        } finally {
            flusher.stop();
        }
    }

    @Test
    void stoppingTwiceIsHarmless() {
        LocationBufferFlusher flusher = start(new LocationBuffer(10, 5, Duration.ofMillis(50)));

        flusher.stop();
        flusher.stop();

        assertThat(flusher.isRunning()).isFalse();
    }

    private LocationBufferFlusher start(LocationBuffer buffer) {
        LocationBufferFlusher flusher = new LocationBufferFlusher(buffer, sink, metrics(), 2000);
        flusher.start();
        return flusher;
    }

    private static LocationMessage message(String userId) {
        return new LocationMessage("company-1", userId, 23.78, 90.40, NOW, NOW, null, null, null);
    }

    /** Real metrics against an in-memory registry - no mocking, and the counters stay exercised. */
    private static PingMetrics metrics() {
        return new PingMetrics(new SimpleMeterRegistry(), new LocationBuffer(1, 1, Duration.ofMillis(1)));
    }

    private static final class CollectingSink implements LocationBatchSink {

        private final List<List<LocationMessage>> batches = new CopyOnWriteArrayList<>();

        @Override
        public void send(List<LocationMessage> batch) {
            batches.add(batch);
        }

        List<LocationMessage> received() {
            return batches.stream().flatMap(List::stream).toList();
        }

        int batchCount() {
            return batches.size();
        }
    }

    private static final class FailingThenWorkingSink implements LocationBatchSink {

        private final AtomicInteger attempts = new AtomicInteger();
        private final List<LocationMessage> delivered = new CopyOnWriteArrayList<>();

        @Override
        public void send(List<LocationMessage> batch) {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("broker unavailable");
            }
            delivered.addAll(batch);
        }
    }
}
