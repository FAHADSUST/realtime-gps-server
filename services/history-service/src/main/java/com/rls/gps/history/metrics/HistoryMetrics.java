package com.rls.gps.history.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * What an operator needs to see about the consumer.
 *
 * <p>Rejected fixes get their own counter rather than being folded into failures: a rising reject
 * count means a producer is sending rubbish, which is a different call-out from the broker or the
 * database being unhealthy.
 */
@Component
public class HistoryMetrics {

    private final Counter batchesConsumed;
    private final Counter fixesStored;
    private final Counter fixesRejected;

    public HistoryMetrics(MeterRegistry registry) {
        this.batchesConsumed = Counter.builder("gps.history.batches.consumed")
                .description("Batches taken off the queue and stored").register(registry);
        this.fixesStored = Counter.builder("gps.history.fixes.stored")
                .description("Fixes written to the database").register(registry);
        this.fixesRejected = Counter.builder("gps.history.fixes.rejected")
                .description("Fixes discarded as unstorable - a producer problem, not an outage")
                .register(registry);
    }

    public void batchConsumed(int stored, int rejected) {
        batchesConsumed.increment();
        if (stored > 0) {
            fixesStored.increment(stored);
        }
        if (rejected > 0) {
            fixesRejected.increment(rejected);
        }
    }
}
