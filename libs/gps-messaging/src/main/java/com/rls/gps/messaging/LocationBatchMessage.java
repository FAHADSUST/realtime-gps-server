package com.rls.gps.messaging;

import java.time.Instant;
import java.util.List;

/**
 * A batch of fixes, which is the unit the pipeline moves.
 *
 * <p>One message per fix would mean one publish, one delivery and one acknowledgement each; at the
 * throughput the original specification reports, the per-message overhead dominates. Batching trades
 * a little latency for an order of magnitude in cost.
 *
 * @param batchId    identifies this batch in logs on both sides, and lets the consumer recognise a
 *                   redelivery
 * @param publishedAt when the ping service handed it to the broker
 */
public record LocationBatchMessage(String batchId, Instant publishedAt, List<LocationMessage> locations) {

    public int size() {
        return locations == null ? 0 : locations.size();
    }
}
