package com.rls.gps.ping.location;

/**
 * A user found inside a search radius.
 *
 * @param distance how far from the centre, in the unit the caller asked for
 */
public record NearbyUser(LastLocation location, double distance) {
}
