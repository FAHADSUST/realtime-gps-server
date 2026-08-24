package com.rls.gps.ping.location;

import java.time.Instant;

/**
 * The JSON actually stored in Redis.
 *
 * <p>Timestamps are epoch milliseconds rather than ISO-8601 strings so the Lua upsert can compare
 * them with {@code tonumber} instead of parsing dates inside Redis. Keeping this separate from
 * {@link LastLocation} means the storage format can change without touching the API contract.
 */
record StoredLocation(String userId,
                      double latitude,
                      double longitude,
                      long recordedAt,
                      long receivedAt,
                      Double accuracy,
                      Double speed,
                      Double heading) {

    static StoredLocation of(String userId, LocationPoint point, Instant receivedAt) {
        return new StoredLocation(userId, point.latitude(), point.longitude(),
                point.recordedAt().toEpochMilli(), receivedAt.toEpochMilli(),
                point.accuracy(), point.speed(), point.heading());
    }

    LastLocation toLastLocation() {
        return new LastLocation(userId, latitude, longitude,
                Instant.ofEpochMilli(recordedAt), Instant.ofEpochMilli(receivedAt),
                accuracy, speed, heading);
    }
}
