package com.rls.gps.history.location;

import java.time.Instant;

/**
 * One stored position.
 *
 * <p>Deliberately not the same type as the message on the queue: the database schema should not
 * change shape because a field on the wire was renamed, and the consumer is the only place that
 * knows about both.
 */
public record LocationFix(String companyId,
                          String userId,
                          double latitude,
                          double longitude,
                          Instant recordedAt,
                          Instant receivedAt,
                          Double accuracy,
                          Double speed,
                          Double heading) {
}
