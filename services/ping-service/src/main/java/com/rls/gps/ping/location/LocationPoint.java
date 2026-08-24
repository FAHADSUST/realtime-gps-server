package com.rls.gps.ping.location;

import java.time.Instant;

/**
 * One position fix as reported by a device.
 *
 * @param recordedAt when the device took the fix - not when the server received it. The two differ
 *                   by seconds normally and by hours when a device flushes an offline buffer.
 */
public record LocationPoint(double latitude,
                            double longitude,
                            Instant recordedAt,
                            Double accuracy,
                            Double speed,
                            Double heading) {
}
