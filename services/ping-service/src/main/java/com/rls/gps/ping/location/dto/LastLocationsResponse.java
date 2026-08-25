package com.rls.gps.ping.location.dto;

import java.util.List;

import com.rls.gps.ping.location.LastLocation;

/**
 * @param locations one entry per user that has a position stored
 * @param missing   users that were asked about but have none - never reported, or reported so long
 *                  ago that the entry expired. Reported explicitly so a client does not have to
 *                  diff the two lists to find out.
 */
public record LastLocationsResponse(List<LastLocation> locations, List<String> missing) {
}
