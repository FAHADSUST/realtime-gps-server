package com.rls.gps.messaging;

import java.time.Instant;

/**
 * One position fix on the wire.
 *
 * <p>Every message carries its own company and user: a batch is assembled from whatever the ping
 * service buffered, so one batch routinely mixes users and companies. Putting the owner on the
 * envelope instead would make the consumer's job impossible.
 *
 * @param recordedAt when the device took the fix
 * @param receivedAt when the platform accepted it - the gap between the two is how far behind a
 *                   device was running
 */
public record LocationMessage(String companyId,
                              String userId,
                              double latitude,
                              double longitude,
                              Instant recordedAt,
                              Instant receivedAt,
                              Double accuracy,
                              Double speed,
                              Double heading) {
}
