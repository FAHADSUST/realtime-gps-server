package com.rls.gps.ping.metrics;

import com.rls.gps.ping.publish.LocationBuffer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * What an operator needs to see about the write path.
 *
 * <p>Buffer occupancy is the leading indicator: it rises before anything is dropped, so an alert on
 * it fires while there is still time to react. The drop counter is the lagging one - by the time it
 * moves, data is already gone.
 */
@Component
public class PingMetrics {

    private final Counter accepted;
    private final Counter dropped;
    private final Counter batchesPublished;
    private final Counter batchesFailed;
    private final Counter batchesNacked;

    public PingMetrics(MeterRegistry registry, LocationBuffer buffer) {
        Gauge.builder("gps.ping.buffer.size", buffer, LocationBuffer::size)
                .description("Fixes waiting to be published")
                .register(registry);
        Gauge.builder("gps.ping.buffer.remaining", buffer, LocationBuffer::remainingCapacity)
                .description("Room left in the buffer before fixes are dropped")
                .register(registry);

        this.accepted = Counter.builder("gps.ping.locations.accepted")
                .description("Fixes taken for publication").register(registry);
        this.dropped = Counter.builder("gps.ping.locations.dropped")
                .description("Fixes lost because the buffer was full").register(registry);
        this.batchesPublished = Counter.builder("gps.ping.batches.published")
                .description("Batches handed to the broker").register(registry);
        this.batchesFailed = Counter.builder("gps.ping.batches.failed")
                .description("Batches the publisher could not hand over").register(registry);
        this.batchesNacked = Counter.builder("gps.ping.batches.nacked")
                .description("Batches the broker refused after accepting them").register(registry);
    }

    public void locationsAccepted(int count) {
        accepted.increment(count);
    }

    public void locationsDropped(int count) {
        dropped.increment(count);
    }

    public void batchPublished() {
        batchesPublished.increment();
    }

    public void batchFailed() {
        batchesFailed.increment();
    }

    public void batchNacked() {
        batchesNacked.increment();
    }
}
