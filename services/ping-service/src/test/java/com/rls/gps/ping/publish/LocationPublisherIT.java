package com.rls.gps.ping.publish;

import java.time.Instant;
import java.util.List;

import com.rls.gps.messaging.LocationBatchMessage;
import com.rls.gps.messaging.LocationMessage;
import com.rls.gps.messaging.LocationTopology;
import com.rls.gps.ping.support.AbstractPingServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** The publisher and the declared topology, against a real broker. */
class LocationPublisherIT extends AbstractPingServiceIT {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    @Autowired
    private LocationPublisher publisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void publishesABatchThatLandsOnTheHistoryQueue() {
        publisher.send(List.of(
                message("company-1", "user-1", 23.78, 90.40),
                message("company-1", "user-2", 23.79, 90.41)));

        LocationBatchMessage received = receiveBatch();

        assertThat(received).isNotNull();
        assertThat(received.batchId()).isNotBlank();
        assertThat(received.publishedAt()).isNotNull();
        assertThat(received.size()).isEqualTo(2);
        assertThat(received.locations()).extracting(LocationMessage::userId)
                .containsExactly("user-1", "user-2");
    }

    @Test
    void carriesEveryFieldAcrossTheWire() {
        publisher.send(List.of(new LocationMessage("company-1", "user-1", 23.7808, 90.4019,
                NOW, NOW.plusSeconds(1), 5.0, 12.5, 180.0)));

        LocationMessage received = receiveBatch().locations().get(0);

        assertThat(received.companyId()).isEqualTo("company-1");
        assertThat(received.latitude()).isEqualTo(23.7808);
        assertThat(received.longitude()).isEqualTo(90.4019);
        assertThat(received.recordedAt()).isEqualTo(NOW);
        assertThat(received.receivedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(received.accuracy()).isEqualTo(5.0);
        assertThat(received.speed()).isEqualTo(12.5);
        assertThat(received.heading()).isEqualTo(180.0);
    }

    @Test
    void oneBatchIsOneMessageRegardlessOfSize() {
        publisher.send(List.of(
                message("company-1", "user-1", 1.0, 1.0),
                message("company-1", "user-2", 2.0, 2.0),
                message("company-1", "user-3", 3.0, 3.0)));

        assertThat(receiveBatch().size()).isEqualTo(3);
        assertThat(rabbitTemplate.receive(LocationTopology.QUEUE, 200))
                .as("a batch must not be split into one message per fix")
                .isNull();
    }

    @Test
    void sendsNothingForAnEmptyBatch() {
        publisher.send(List.of());

        assertThat(rabbitTemplate.receive(LocationTopology.QUEUE, 200)).isNull();
    }

    @Test
    void theDeadLetterQueueExists() {
        // Declared up front so a poison batch has somewhere to go the first time it appears.
        Message none = rabbitTemplate.receive(LocationTopology.DEAD_LETTER_QUEUE, 200);

        assertThat(none).as("queue exists but is empty").isNull();
    }

    private LocationBatchMessage receiveBatch() {
        return (LocationBatchMessage) rabbitTemplate.receiveAndConvert(LocationTopology.QUEUE, 5000);
    }

    private static LocationMessage message(String companyId, String userId, double lat, double lon) {
        return new LocationMessage(companyId, userId, lat, lon, NOW, NOW, null, null, null);
    }
}
