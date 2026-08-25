package com.rls.gps.ping.location.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * A batch of fixes for the authenticated user. A device reporting a single position sends a batch
 * of one; a device flushing an offline buffer sends many.
 *
 * <p>The size cap exists so one request cannot occupy a worker thread indefinitely on the hot path.
 */
public record LocationBatchRequest(

        @NotEmpty
        @Size(max = LocationBatchRequest.MAX_BATCH_SIZE,
                message = "may contain at most " + LocationBatchRequest.MAX_BATCH_SIZE + " locations")
        @Valid
        List<LocationPointRequest> locations) {

    public static final int MAX_BATCH_SIZE = 500;
}
