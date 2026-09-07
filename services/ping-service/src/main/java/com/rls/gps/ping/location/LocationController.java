package com.rls.gps.ping.location;

import java.util.List;

import com.rls.gps.common.security.CurrentIdentity;
import com.rls.gps.common.security.Identity;
import com.rls.gps.ping.location.dto.LastLocationsResponse;
import com.rls.gps.ping.location.dto.LocationBatchRequest;
import com.rls.gps.ping.location.dto.LocationBatchResponse;
import com.rls.gps.ping.location.dto.NearbyUsersResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/locations")
@Validated
public class LocationController {

    /** A fan-out read: without a cap, one request could ask for every user a company has. */
    static final int MAX_USERS_PER_QUERY = 100;

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

    /**
     * The last known position of specific users in the caller's company.
     *
     * <p>Accepts {@code ?userIds=a,b,c} or repeated {@code ?userIds=} parameters. The cap exists
     * because this is a fan-out read: unbounded, one request could ask for every user a company has.
     */
    /**
     * The spec's "retrieve all users within a specific radius", scoped to the caller's company.
     *
     * <p>Only users with a live last location are returned: a device that stopped reporting drops
     * out once its entry expires, rather than haunting the map at its final position.
     */
    @GetMapping("/users")
    public NearbyUsersResponse nearbyUsers(
            @CurrentIdentity Identity caller,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lon,
            @RequestParam @Positive double radius,
            @RequestParam(defaultValue = "KM") RadiusUnit unit,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_USERS_PER_QUERY) int limit) {
        return locationService.nearbyUsers(caller, lat, lon, radius, unit, limit);
    }

    @GetMapping
    public LastLocationsResponse lastLocations(
            @CurrentIdentity Identity caller,
            @RequestParam @NotEmpty @Size(max = MAX_USERS_PER_QUERY,
                    message = "may ask about at most " + MAX_USERS_PER_QUERY + " users") List<String> userIds) {
        return locationService.lastLocations(caller, userIds);
    }
}
