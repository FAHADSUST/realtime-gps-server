package com.rls.gps.ping.location;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rls.gps.ping.config.PingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.domain.geo.GeoShape;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Each user's last known position, plus the company geo index radius queries run against.
 *
 * <p>Keys:
 * <pre>
 *   {prefix}:{companyId}:last:{userId}   JSON, expires after gps.ping.redis.last-location-ttl
 *   {prefix}:{companyId}:geo             GEO set, member = userId
 * </pre>
 *
 * <p>The geo index has no per-member TTL - Redis does not offer one - so a member can outlive the
 * location it points at. Radius queries therefore treat the location key as the source of truth and
 * ignore members whose location has expired.
 */
@Repository
public class LastLocationRepository {

    private static final Logger log = LoggerFactory.getLogger(LastLocationRepository.class);

    /** Ask the index for more than the caller wants, because expired members will be discarded. */
    private static final int STALE_OVERFETCH_FACTOR = 2;

    /** Hard ceiling on one geo scan, so a huge radius cannot pull an entire company into memory. */
    private static final long MAX_GEO_SCAN = 2000;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> upsertScript;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final long ttlSeconds;

    public LastLocationRepository(StringRedisTemplate redis,
                                  RedisScript<Long> lastLocationUpsertScript,
                                  ObjectMapper objectMapper,
                                  PingProperties properties) {
        this.redis = redis;
        this.upsertScript = lastLocationUpsertScript;
        this.objectMapper = objectMapper;
        this.keyPrefix = properties.redis().keyPrefix();
        this.ttlSeconds = properties.redis().lastLocationTtl().toSeconds();
    }

    /**
     * Stores a point as the user's last known location, unless a newer one is already stored.
     *
     * @return true when this point became the last known location
     */
    public boolean save(String companyId, String userId, LocationPoint point, Instant receivedAt) {
        String json = serialize(StoredLocation.of(userId, point, receivedAt));

        Long stored = redis.execute(upsertScript,
                List.of(lastLocationKey(companyId, userId), geoKey(companyId)),
                json,
                Long.toString(point.recordedAt().toEpochMilli()),
                userId,
                Long.toString(ttlSeconds),
                Double.toString(point.longitude()),
                Double.toString(point.latitude()));

        return stored != null && stored == 1L;
    }

    /**
     * Reads several users' last locations in a single round trip. Users with nothing stored - never
     * reported, or expired - are simply absent from the result.
     */
    public List<LastLocation> findByUserIds(String companyId, Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }

        List<String> keys = userIds.stream().map(userId -> lastLocationKey(companyId, userId)).toList();
        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null) {
            return List.of();
        }

        List<LastLocation> locations = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null) {
                continue;
            }
            deserialize(value).ifPresent(stored -> locations.add(stored.toLastLocation()));
        }
        return locations;
    }

    /**
     * Users whose last known position falls inside the radius, nearest first.
     *
     * <p>The geo index can name users whose location key has since expired - Redis has no per-member
     * TTL - so every hit is checked against the location itself, and members that no longer have one
     * are removed on the way past. That keeps the index from growing forever with devices that
     * stopped reporting months ago, without a sweeper job.
     *
     * <p>Consequence worth knowing: the index is searched for more members than requested, because
     * some will be discarded. With an unusual number of expired members a query can still return
     * fewer than {@code limit} users while more exist further out; answering that exactly needs a
     * cursor, which the endpoint does not offer.
     */
    public List<NearbyUser> findWithinRadius(String companyId, double latitude, double longitude,
                                             double radius, RadiusUnit unit, int limit) {
        GeoSearchCommandArgs args = GeoSearchCommandArgs.newGeoSearchArgs()
                .includeDistance()
                .sortAscending()
                .limit(Math.min((long) limit * STALE_OVERFETCH_FACTOR, MAX_GEO_SCAN));

        GeoResults<GeoLocation<String>> results = redis.opsForGeo().search(
                geoKey(companyId),
                GeoReference.fromCoordinate(new Point(longitude, latitude)),
                GeoShape.byRadius(new Distance(radius, unit.metric())),
                args);

        if (results == null || results.getContent().isEmpty()) {
            return List.of();
        }

        List<String> userIds = results.getContent().stream()
                .map(result -> result.getContent().getName())
                .toList();

        Map<String, LastLocation> live = findByUserIds(companyId, userIds).stream()
                .collect(Collectors.toMap(LastLocation::userId, location -> location));

        List<NearbyUser> nearby = new ArrayList<>(Math.min(limit, userIds.size()));
        List<String> expired = new ArrayList<>();

        for (GeoResult<GeoLocation<String>> result : results.getContent()) {
            String userId = result.getContent().getName();
            LastLocation location = live.get(userId);

            if (location == null) {
                expired.add(userId);
                continue;
            }
            if (nearby.size() < limit) {
                nearby.add(new NearbyUser(location, result.getDistance().getValue()));
            }
        }

        evictExpired(companyId, expired);
        return nearby;
    }

    /** Lazy cleanup: a member whose location expired can never be a useful search hit again. */
    private void evictExpired(String companyId, List<String> expired) {
        if (expired.isEmpty()) {
            return;
        }
        try {
            redis.opsForZSet().remove(geoKey(companyId), expired.toArray());
            log.debug("geo_members_evicted companyId={} count={}", companyId, expired.size());
        } catch (RuntimeException ex) {
            // Cleanup is opportunistic; failing it must not fail the caller's query.
            log.warn("geo_member_eviction_failed companyId={} count={}", companyId, expired.size(), ex);
        }
    }

    String lastLocationKey(String companyId, String userId) {
        return keyPrefix + ":" + companyId + ":last:" + userId;
    }

    String geoKey(String companyId) {
        return keyPrefix + ":" + companyId + ":geo";
    }

    private String serialize(StoredLocation stored) {
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("could not serialise a location", ex);
        }
    }

    private java.util.Optional<StoredLocation> deserialize(String json) {
        try {
            return java.util.Optional.of(objectMapper.readValue(json, StoredLocation.class));
        } catch (JsonProcessingException ex) {
            // A single unreadable entry (an old format, a manual edit) must not fail the whole read.
            log.warn("unreadable_location_entry ignored: {}", ex.getOriginalMessage());
            return java.util.Optional.empty();
        }
    }
}
