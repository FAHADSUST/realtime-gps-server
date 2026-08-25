package com.rls.gps.ping.location;

import com.rls.gps.common.security.CurrentIdentity;
import com.rls.gps.common.security.Identity;
import com.rls.gps.ping.location.dto.LocationBatchRequest;
import com.rls.gps.ping.location.dto.LocationBatchResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/locations")
public class LocationController {

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    /**
     * Records the caller's positions.
     *
     * <p>202 rather than 201: the fixes are accepted for processing. The last known position is
     * updated before responding, but durable history is written asynchronously by the History
     * service, so there is no resource to point at yet.
     *
     * <p>The locations are always the caller's own - the user id comes from the verified identity,
     * never the payload, so no device can report a position on someone else's behalf.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public LocationBatchResponse submit(@CurrentIdentity Identity caller,
                                        @Valid @RequestBody LocationBatchRequest request) {
        return locationService.ingest(caller, request);
    }
}
