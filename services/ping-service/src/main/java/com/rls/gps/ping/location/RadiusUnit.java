package com.rls.gps.ping.location;

import org.springframework.data.geo.Metric;
import org.springframework.data.redis.connection.RedisGeoCommands;

/**
 * The units a radius query may use, exposed as an API enum so an unknown one is a 400 rather than a
 * silent reinterpretation.
 *
 * <p>Spring Data's own {@code Metrics} only covers kilometres and miles; Redis also understands
 * metres and feet, which is what {@code RedisGeoCommands.DistanceUnit} provides.
 */
public enum RadiusUnit {

    M(RedisGeoCommands.DistanceUnit.METERS),
    KM(RedisGeoCommands.DistanceUnit.KILOMETERS),
    MI(RedisGeoCommands.DistanceUnit.MILES),
    FT(RedisGeoCommands.DistanceUnit.FEET);

    private final Metric metric;

    RadiusUnit(Metric metric) {
        this.metric = metric;
    }

    public Metric metric() {
        return metric;
    }
}
