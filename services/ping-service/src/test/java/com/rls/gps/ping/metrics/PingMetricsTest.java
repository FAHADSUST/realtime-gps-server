package com.rls.gps.ping.metrics;

import java.time.Duration;
import java.time.Instant;

import com.rls.gps.messaging.LocationMessage;
import com.rls.gps.ping.publish.LocationBuffer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PingMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final LocationBuffer buffer = new LocationBuffer(10, 5, Duration.ofMillis(50));
    private final PingMetrics metrics = new PingMetrics(registry, buffer);

    @Test
    void bufferOccupancyIsVisibleBeforeAnythingIsLost() {
        assertThat(gauge("gps.ping.buffer.size")).isZero();
        assertThat(gauge("gps.ping.buffer.remaining")).isEqualTo(10);

        buffer.offer(message());
        buffer.offer(message());

        // The leading indicator: it moves while there is still room to react.
        assertThat(gauge("gps.ping.buffer.size")).isEqualTo(2);
        assertThat(gauge("gps.ping.buffer.remaining")).isEqualTo(8);
    }

    @Test
    void countsWhatWasAcceptedAndWhatWasLost() {
        metrics.locationsAccepted(7);
        metrics.locationsDropped(3);

        assertThat(counter("gps.ping.locations.accepted")).isEqualTo(7);
        assertThat(counter("gps.ping.locations.dropped")).isEqualTo(3);
    }

    @Test
    void separatesPublishFailuresFromBrokerRejections() {
        metrics.batchPublished();
        metrics.batchPublished();
        metrics.batchFailed();
        metrics.batchNacked();

        assertThat(counter("gps.ping.batches.published")).isEqualTo(2);
        // "Could not hand it over" and "the broker took it then refused" are different problems.
        assertThat(counter("gps.ping.batches.failed")).isEqualTo(1);
        assertThat(counter("gps.ping.batches.nacked")).isEqualTo(1);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    private static LocationMessage message() {
        Instant now = Instant.parse("2026-09-16T10:00:00Z");
        return new LocationMessage("company-1", "user-1", 23.78, 90.40, now, now, null, null, null);
    }
}
