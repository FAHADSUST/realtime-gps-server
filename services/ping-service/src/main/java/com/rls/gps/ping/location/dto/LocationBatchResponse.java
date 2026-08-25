package com.rls.gps.ping.location.dto;

/**
 * @param accepted            how many fixes were taken in
 * @param lastLocationUpdated whether any of them was newer than the stored position. False means
 *                            the batch was entirely historical - useful, not an error.
 */
public record LocationBatchResponse(int accepted, boolean lastLocationUpdated) {
}
