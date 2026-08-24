package com.rls.gps.ping.location;

import java.time.Instant;

/** A user's last known position, as read back from Redis. */
public record LastLocation(String userId,
                           double latitude,
                           double longitude,
                           Instant recordedAt,
                           Instant receivedAt,
                           Double accuracy,
                           Double speed,
                           Double heading) {
}
