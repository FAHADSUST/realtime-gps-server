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

    /**
     * Converts a radius in this unit to kilometres, so one configured ceiling can bound a query
     * whatever unit it was written in - a cap expressed per unit would let "5000000 m" through.
     */
    public double toKilometres(double radius) {
        return switch (this) {
            case M -> radius / 1_000d;
            case KM -> radius;
            case MI -> radius * 1.609344d;
            case FT -> radius * 0.0003048d;
        };
    }
}
