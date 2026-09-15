package com.rls.gps.history.consume;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import com.rls.gps.history.support.AbstractHistoryServiceIT;
import com.rls.gps.messaging.LocationBatchMessage;
import com.rls.gps.messaging.LocationMessage;
import com.rls.gps.messaging.LocationTopology;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The pipeline's receiving end: a batch on the queue becomes rows in MySQL. */
class LocationBatchConsumerIT extends AbstractHistoryServiceIT {

    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z").truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void storesEveryFixInAPublishedBatch() {
        publish(List.of(
                message("company-1", "user-1", 23.78, 90.40, NOW),
                message("company-1", "user-1", 23.79, 90.41, NOW.plusSeconds(60)),
                message("company-1", "user-2", 23.70, 90.30, NOW)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(rowCount()).isEqualTo(3));
    }

    @Test
    void storesTheFixExactlyAsItWasSent() {
        publish(List.of(new LocationMessage("company-1", "user-1", 23.7808, 90.4019,
                NOW, NOW.plusSeconds(1), 5.0, 12.5, 180.0)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(rowCount()).isEqualTo(1));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM user_location");
        assertThat(row.get("company_id")).isEqualTo("company-1");
        assertThat(row.get("user_id")).isEqualTo("user-1");
        assertThat((Double) row.get("latitude")).isEqualTo(23.7808);
        assertThat((Double) row.get("accuracy")).isEqualTo(5.0);
        assertThat(((LocalDateTime) row.get("recorded_at")).toInstant(ZoneOffset.UTC)).isEqualTo(NOW);
    }

    @Test
    void consumesSeveralBatchesInARow() {
        IntStream.range(0, 5).forEach(batchIndex ->
                publish(IntStream.range(0, 20)
                        .mapToObj(index -> message("company-1", "user-" + batchIndex,
                                23.0, 90.0, NOW.plusSeconds(index)))
                        .toList()));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(rowCount()).isEqualTo(100));
    }

    @Test
    void anEmptyBatchIsAcknowledgedRatherThanRetried() {
        rabbitTemplate.convertAndSend(LocationTopology.EXCHANGE, LocationTopology.ROUTING_KEY,
                new LocationBatchMessage(UUID.randomUUID().toString(), NOW, List.of()));

        // Nothing to store, and nothing that retrying would fix: the queue must drain.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(rowCount()).isZero();
            assertThat(rabbitTemplate.receive(LocationTopology.QUEUE, 200)).isNull();
        });
    }

    private void publish(List<LocationMessage> locations) {
        rabbitTemplate.convertAndSend(LocationTopology.EXCHANGE, LocationTopology.ROUTING_KEY,
                new LocationBatchMessage(UUID.randomUUID().toString(), NOW, locations));
    }

    private static LocationMessage message(String companyId, String userId, double lat, double lon,
                                           Instant recordedAt) {
        return new LocationMessage(companyId, userId, lat, lon, recordedAt, recordedAt.plusSeconds(1),
                null, null, null);
    }
}
