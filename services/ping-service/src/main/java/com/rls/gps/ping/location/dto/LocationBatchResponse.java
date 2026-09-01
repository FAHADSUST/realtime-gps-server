package com.rls.gps.ping.location.dto;

/**
 * @param accepted            fixes taken for durable processing
 * @param dropped             fixes the buffer had no room for. Reported rather than hidden: a client
 *                            that sees this knows its history has a gap, which is the only honest
 *                            answer when the platform is shedding load.
 * @param lastLocationUpdated whether any of them was newer than the stored position. False means
 *                            the batch was entirely historical - useful, not an error.
 */
public record LocationBatchResponse(int accepted, int dropped, boolean lastLocationUpdated) {
}
