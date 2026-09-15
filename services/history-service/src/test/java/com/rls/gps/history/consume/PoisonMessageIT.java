package com.rls.gps.history.consume;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.rls.gps.history.support.AbstractHistoryServiceIT;
import com.rls.gps.messaging.LocationBatchMessage;
import com.rls.gps.messaging.LocationMessage;
import com.rls.gps.messaging.LocationTopology;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * How the consumer distinguishes data it can never store from a failure worth retrying.
 */
class PoisonMessageIT extends AbstractHistoryServiceIT {

    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z").truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void oneMalformedFixDoesNotCostTheGoodOnesBesideIt() {
        publish(List.of(
                message("company-1", "user-1", 23.78, 90.40),
                message(null, "user-2", 23.79, 90.41),          // no owner: unstorable, ever
                message("company-1", "user-3", 999, 90.42),     // off the globe
                message("company-1", "user-4", 23.70, 90.30)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(rowCount()).isEqualTo(2));

        assertThat(jdbc.queryForList("SELECT user_id FROM user_location", String.class))
                .containsExactlyInAnyOrder("user-1", "user-4");
    }

    @Test
    void aBatchOfNothingButRubbishIsStillAcknowledged() {
        publish(List.of(message(null, null, 0, 0)));

        // Retrying would never help, so the queue must drain rather than dead-letter.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(rowCount()).isZero();
            assertThat(rabbitTemplate.receive(LocationTopology.QUEUE, 200)).isNull();
        });
    }

    @Test
    void anUnreadableMessageEndsUpOnTheDeadLetterQueue() {
        // Not JSON at all - the kind of thing a wrong producer or a bad deploy puts on a queue.
        // It cannot be parsed, so it cannot be retried into success; it must not block the queue.
        Message unreadable = MessageBuilder
                .withBody("this is not a location batch".getBytes(StandardCharsets.UTF_8))
                .andProperties(jsonProperties())
                .build();

        rabbitTemplate.send(LocationTopology.EXCHANGE, LocationTopology.ROUTING_KEY, unreadable);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(rabbitTemplate.receive(LocationTopology.DEAD_LETTER_QUEUE, 500))
                        .as("an unreadable message belongs in the dead-letter queue")
                        .isNotNull());
    }

    @Test
    void theQueueKeepsWorkingAfterAPoisonMessage() {
        publish(List.of(message(null, null, 0, 0)));
        publish(List.of(message("company-1", "user-9", 23.78, 90.40)));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(rowCount()).isEqualTo(1));
    }

    private static MessageProperties jsonProperties() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return properties;
    }

    private void publish(List<LocationMessage> locations) {
        rabbitTemplate.convertAndSend(LocationTopology.EXCHANGE, LocationTopology.ROUTING_KEY,
                new LocationBatchMessage(UUID.randomUUID().toString(), NOW, locations));
    }

    private static LocationMessage message(String companyId, String userId, double lat, double lon) {
        return new LocationMessage(companyId, userId, lat, lon, NOW, NOW.plusSeconds(1), null, null, null);
    }
}
