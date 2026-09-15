package com.rls.gps.history.consume;

import java.util.List;

import com.rls.gps.history.location.LocationFix;
import com.rls.gps.history.location.LocationHistoryRepository;
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

    public LocationBatchConsumer(LocationHistoryRepository repository) {
        this.repository = repository;
    }

    @RabbitListener(queues = LocationTopology.QUEUE)
    public void onBatch(LocationBatchMessage batch) {
        if (batch == null || batch.locations() == null || batch.locations().isEmpty()) {
            // Nothing to store and nothing to retry: acknowledge and move on.
            log.warn("empty_location_batch batchId={}", batch == null ? "null" : batch.batchId());
            return;
        }

        List<LocationFix> fixes = batch.locations().stream()
                .map(LocationBatchConsumer::toFix)
                .toList();

        int stored = repository.insertAll(fixes);

        log.debug("location_batch_stored batchId={} size={}", batch.batchId(), stored);
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
