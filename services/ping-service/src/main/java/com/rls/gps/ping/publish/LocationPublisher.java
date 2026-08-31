package com.rls.gps.ping.publish;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.rls.gps.messaging.LocationBatchMessage;
import com.rls.gps.messaging.LocationMessage;
import com.rls.gps.messaging.LocationTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes buffered batches to RabbitMQ.
 *
 * <p>Confirms are handled asynchronously: waiting for the broker to acknowledge every batch would
 * serialise the flush loop behind a network round trip and cap throughput at a fraction of what the
 * spec reports. Instead a nack or a return is logged and counted, which is the honest trade - see
 * "Delivery guarantees" in the README.
 */
@Component
public class LocationPublisher implements LocationBatchSink {

    private static final Logger log = LoggerFactory.getLogger(LocationPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final Clock clock;

    public LocationPublisher(RabbitTemplate rabbitTemplate, Clock clock) {
        this.rabbitTemplate = rabbitTemplate;
        this.clock = clock;
    }

    @Override
    public void send(List<LocationMessage> batch) {
        if (batch.isEmpty()) {
            return;
        }

        String batchId = UUID.randomUUID().toString();
        LocationBatchMessage message = new LocationBatchMessage(batchId, clock.instant(), List.copyOf(batch));

        rabbitTemplate.convertAndSend(LocationTopology.EXCHANGE, LocationTopology.ROUTING_KEY, message,
                new CorrelationData(batchId));

        log.debug("location_batch_published batchId={} size={}", batchId, batch.size());
    }
}
