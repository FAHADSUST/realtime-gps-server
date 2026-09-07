package com.rls.gps.ping.location.dto;

import java.time.Instant;
import java.util.List;

import com.rls.gps.ping.location.NearbyUser;
import com.rls.gps.ping.location.RadiusUnit;

/**
 * Users found inside a radius, nearest first.
 *
 * <p>The query is echoed back so a client reading a cached or logged response knows what it was an
 * answer to.
 */
public record NearbyUsersResponse(double latitude,
                                  double longitude,
                                  double radius,
                                  RadiusUnit unit,
                                  int count,
                                  List<NearbyUserResponse> users) {

    public static NearbyUsersResponse of(double latitude, double longitude, double radius,
                                         RadiusUnit unit, List<NearbyUser> nearby) {
        List<NearbyUserResponse> users = nearby.stream().map(NearbyUserResponse::from).toList();
        return new NearbyUsersResponse(latitude, longitude, radius, unit, users.size(), users);
    }

    /** Flattened for the client: a user and how far away they are, not a nested location object. */
    public record NearbyUserResponse(String userId,
                                     double latitude,
                                     double longitude,
                                     Instant recordedAt,
                                     Instant receivedAt,
                                     double distance) {

        static NearbyUserResponse from(NearbyUser nearby) {
            return new NearbyUserResponse(
                    nearby.location().userId(),
                    nearby.location().latitude(),
                    nearby.location().longitude(),
                    nearby.location().recordedAt(),
                    nearby.location().receivedAt(),
                    nearby.distance());
        }
    }
}
