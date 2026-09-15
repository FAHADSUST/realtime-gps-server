package com.rls.gps.history.consume;

import java.util.ArrayList;
import java.util.List;

import com.rls.gps.history.location.LocationFix;
import com.rls.gps.history.location.LocationHistoryRepository;
import com.rls.gps.history.metrics.HistoryMetrics;
import com.rls.gps.messaging.LocationBatchMessage;
import com.rls.gps.messaging.LocationMessage;
import com.rls.gps.messaging.LocationTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The queue's only consumer, as the specification requires.
 *
 * <p>Acknowledgement is Spring's default AUTO mode, not MANUAL: the container acknowledges when this
 * method returns and rejects when it throws, which is exactly the semantics wanted and removes the
 * commonest bug in hand-rolled acknowledgement - a path that returns without acking, quietly
 * stalling the queue.
 *
 * <p>The unit of work is the whole batch. One insert per fix would give finer-grained failure but
 * would also undo the batching the ping service went to such lengths to create.
 */
@Component
public class LocationBatchConsumer {

    private static final Logger log = LoggerFactory.getLogger(LocationBatchConsumer.class);

    private final LocationHistoryRepository repository;
    private final HistoryMetrics metrics;

    public LocationBatchConsumer(LocationHistoryRepository repository, HistoryMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    @RabbitListener(queues = LocationTopology.QUEUE)
    public void onBatch(LocationBatchMessage batch) {
        if (batch == null || batch.locations() == null || batch.locations().isEmpty()) {
            // Nothing to store and nothing to retry: acknowledge and move on.
            log.warn("empty_location_batch batchId={}", batch == null ? "null" : batch.batchId());
            return;
        }

        List<LocationFix> storable = new ArrayList<>(batch.locations().size());
        int rejected = 0;

        for (LocationMessage fix : batch.locations()) {
            String reason = FixValidation.rejectionReason(fix);
            if (reason == null) {
                storable.add(toFix(fix));
            } else {
                // Dropped, not dead-lettered: one malformed fix must not cost the 499 good ones
                // beside it, and retrying it would never help.
                rejected++;
                log.warn("location_fix_rejected batchId={} reason={}", batch.batchId(), reason);
            }
        }

        // Anything thrown from here is transient by elimination - the broker or the database - so
        // letting it propagate is correct: the container retries and eventually dead-letters.
        int stored = repository.insertAll(storable);

        metrics.batchConsumed(stored, rejected);
        log.debug("location_batch_stored batchId={} stored={} rejected={}",
                batch.batchId(), stored, rejected);
    }

    private static LocationFix toFix(LocationMessage message) {
        return new LocationFix(
                message.companyId(),
                message.userId(),
                message.latitude(),
                message.longitude(),
                message.recordedAt(),
                message.receivedAt(),
                message.accuracy(),
                message.speed(),
                message.heading());
    }
}
