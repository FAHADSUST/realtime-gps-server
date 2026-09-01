package com.rls.gps.ping.location;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.jayway.jsonpath.JsonPath;
import com.rls.gps.common.web.GpsHeaders;
import com.rls.gps.messaging.LocationBatchMessage;
import com.rls.gps.messaging.LocationMessage;
import com.rls.gps.messaging.LocationTopology;
import com.rls.gps.ping.support.AbstractPingServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole write path: HTTP in, Redis updated, every fix out on the queue for the History service.
 */
class LocationPipelineIT extends AbstractPingServiceIT {

    private static final String COMPANY = "company-1";
    private static final String USER = "user-1";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private LastLocationRepository repository;

    @Test
    void everyFixReachesTheQueueNotJustTheNewest() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        ResponseEntity<String> response = submit(List.of(
                point(23.70, 90.40, base.minusSeconds(120)),
                point(23.75, 90.41, base.minusSeconds(60)),
                point(23.78, 90.42, base)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.accepted")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.dropped")).isZero();

        List<LocationMessage> published = drainQueue(3);

        assertThat(published).hasSize(3);
        assertThat(published).extracting(LocationMessage::latitude)
                .containsExactlyInAnyOrder(23.70, 23.75, 23.78);

        // ...while Redis keeps only the newest.
        assertThat(repository.findByUserIds(COMPANY, List.of(USER)))
                .singleElement()
                .satisfies(location -> assertThat(location.latitude()).isEqualTo(23.78));
    }

    @Test
    void publishedFixesCarryTheVerifiedIdentity() {
        submit(List.of(point(23.78, 90.42, Instant.now())));

        List<LocationMessage> published = drainQueue(1);

        assertThat(published).singleElement().satisfies(message -> {
            assertThat(message.companyId()).isEqualTo(COMPANY);
            assertThat(message.userId()).isEqualTo(USER);
            assertThat(message.receivedAt()).isNotNull();
        });
    }

    @Test
    void manyRequestsAreBatchedTogether() {
        IntStream.range(0, 40).forEach(index ->
                submit(List.of(point(23.0 + index / 1000.0, 90.0, Instant.now()))));

        List<LocationBatchMessage> batches = new ArrayList<>();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            LocationBatchMessage batch =
                    (LocationBatchMessage) rabbitTemplate.receiveAndConvert(LocationTopology.QUEUE, 500);
            if (batch != null) {
                batches.add(batch);
            }
            assertThat(batches.stream().mapToInt(LocationBatchMessage::size).sum()).isEqualTo(40);
        });

        assertThat(batches.size())
                .as("40 single-fix requests should be published as far fewer than 40 messages")
                .isLessThan(40);
    }

    private List<LocationMessage> drainQueue(int expected) {
        List<LocationMessage> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            LocationBatchMessage batch =
                    (LocationBatchMessage) rabbitTemplate.receiveAndConvert(LocationTopology.QUEUE, 500);
            if (batch != null) {
                received.addAll(batch.locations());
            }
            assertThat(received).hasSize(expected);
        });
        return received;
    }

    private ResponseEntity<String> submit(List<Map<String, Object>> points) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(GpsHeaders.COMPANY_ID, COMPANY);
        headers.set(GpsHeaders.USER_ID, USER);

        return rest.exchange("/api/v1/locations", HttpMethod.POST,
                new HttpEntity<>(Map.of("locations", points), headers), String.class);
    }

    private static Map<String, Object> point(double latitude, double longitude, Instant recordedAt) {
        return Map.of("latitude", latitude, "longitude", longitude, "recordedAt", recordedAt.toString());
    }
}
