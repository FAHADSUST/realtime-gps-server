package com.rls.gps.ping.location.dto;

import java.time.Instant;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * One position fix in an incoming batch.
 *
 * <p>Latitude and longitude are boxed {@code Double} rather than primitives on purpose: a primitive
 * would silently default a missing coordinate to {@code 0.0}, which is a real location in the Gulf
 * of Guinea rather than an error. Boxed plus {@code @NotNull} turns the mistake into a 400.
 *
 * @param recordedAt when the device took the fix. Optional - the server's clock is used when it is
 *                   absent - but a device that reports its own time gets accurate history.
 */
public record LocationPointRequest(

        @NotNull
        @DecimalMin(value = "-90.0", message = "must be a valid latitude")
        @DecimalMax(value = "90.0", message = "must be a valid latitude")
        Double latitude,

        @NotNull
        @DecimalMin(value = "-180.0", message = "must be a valid longitude")
        @DecimalMax(value = "180.0", message = "must be a valid longitude")
        Double longitude,

        Instant recordedAt,

        @PositiveOrZero
        Double accuracy,

        @PositiveOrZero
        Double speed,

        @DecimalMin(value = "0.0")
        @DecimalMax(value = "360.0")
        Double heading) {
}
