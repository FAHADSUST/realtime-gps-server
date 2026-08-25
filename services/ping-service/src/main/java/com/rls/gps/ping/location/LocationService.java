package com.rls.gps.ping.location;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.rls.gps.common.error.ApiExceptions;
import com.rls.gps.common.security.Identity;
import com.rls.gps.ping.config.PingProperties;
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
    private final Clock clock;
    private final Duration maxClockSkew;

    public LocationService(LastLocationRepository lastLocations, Clock clock, PingProperties properties) {
        this.lastLocations = lastLocations;
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

        return new LocationBatchResponse(points.size(), updated);
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
