package com.rls.gps.ping.location;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.rls.gps.common.error.ApiExceptions;
import com.rls.gps.common.security.Identity;
import com.rls.gps.messaging.LocationMessage;
import com.rls.gps.ping.config.PingProperties;
import com.rls.gps.ping.metrics.PingMetrics;
import com.rls.gps.ping.publish.LocationAdmission;
import com.rls.gps.ping.location.dto.LastLocationsResponse;
import com.rls.gps.ping.location.dto.LocationBatchRequest;
import com.rls.gps.ping.location.dto.LocationBatchResponse;
import com.rls.gps.ping.location.dto.LocationPointRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LocationService {

    private static final Logger log = LoggerFactory.getLogger(LocationService.class);

    private final LastLocationRepository lastLocations;
    private final LocationAdmission admission;
    private final PingMetrics metrics;
    private final Clock clock;
    private final Duration maxClockSkew;

    public LocationService(LastLocationRepository lastLocations,
                           LocationAdmission admission,
                           PingMetrics metrics,
                           Clock clock,
                           PingProperties properties) {
        this.lastLocations = lastLocations;
        this.admission = admission;
        this.metrics = metrics;
        this.clock = clock;
        this.maxClockSkew = properties.maxClockSkew();
    }

    /**
     * Takes in a batch of fixes and updates the caller's last known position.
     *
     * <p>Only the newest fix in the batch can become the last known location - the rest are history,
     * and C7 sends the whole batch to the queue for the History service. Picking the maximum here
     * rather than writing each point in turn means one Redis round trip instead of N.
     */
    public LocationBatchResponse ingest(Identity caller, LocationBatchRequest request) {
        Instant receivedAt = clock.instant();
        List<LocationPoint> points = request.locations().stream()
                .map(point -> toPoint(point, receivedAt))
                .toList();

        LocationPoint newest = points.stream()
                .max(Comparator.comparing(LocationPoint::recordedAt))
                .orElseThrow();

        boolean updated = lastLocations.save(caller.companyId(), caller.userId(), newest, receivedAt);
        if (!updated) {
            log.debug("last_location_not_updated companyId={} userId={} - stored fix is newer",
                    caller.companyId(), caller.userId());
        }

        int dropped = buffer(caller, points, receivedAt);
        return new LocationBatchResponse(points.size() - dropped, dropped, updated);
    }

    /**
     * Hands the whole batch to the buffer for the History service.
     *
     * <p>Every fix goes to the queue, not just the newest: Redis holds the current position, the
     * queue carries the track. The request returns once they are in memory - durable storage is the
     * History service's job.
     */
    private int buffer(Identity caller, List<LocationPoint> points, Instant receivedAt) {
        List<LocationMessage> messages = points.stream()
                .map(point -> new LocationMessage(caller.companyId(), caller.userId(),
                        point.latitude(), point.longitude(), point.recordedAt(), receivedAt,
                        point.accuracy(), point.speed(), point.heading()))
                .toList();

        int dropped = admission.admit(messages);

        metrics.locationsAccepted(messages.size() - dropped);
        if (dropped > 0) {
            metrics.locationsDropped(dropped);
            log.warn("location_buffer_full companyId={} userId={} dropped={} policy={}",
                    caller.companyId(), caller.userId(), dropped, admission.policy());
        }
        return dropped;
    }

    /**
     * Reads several users' last known positions, scoped to the caller's company.
     *
     * <p>Duplicates in the request are collapsed, so asking for the same user ten times costs one
     * lookup, and users with nothing stored come back in {@code missing} rather than being silently
     * dropped.
     */
    public LastLocationsResponse lastLocations(Identity caller, List<String> userIds) {
        LinkedHashSet<String> requested = new LinkedHashSet<>(userIds);

        List<LastLocation> found = lastLocations.findByUserIds(caller.companyId(), requested);

        Set<String> foundIds = found.stream().map(LastLocation::userId).collect(Collectors.toSet());
        List<String> missing = requested.stream().filter(id -> !foundIds.contains(id)).toList();

        return new LastLocationsResponse(found, missing);
    }

    private LocationPoint toPoint(LocationPointRequest request, Instant receivedAt) {
        Instant recordedAt = request.recordedAt() == null ? receivedAt : request.recordedAt();

        // A device with a wrong clock is not a curiosity here: "newest fix wins" means one point
        // dated in 2099 would block every real update for that user until it expired. Reject it.
        if (recordedAt.isAfter(receivedAt.plus(maxClockSkew))) {
            throw ApiExceptions.badRequest("location_timestamp_in_future",
                    "recordedAt is more than " + maxClockSkew.toMinutes() + " minutes in the future");
        }

        return new LocationPoint(request.latitude(), request.longitude(), recordedAt,
                request.accuracy(), request.speed(), request.heading());
    }
}
