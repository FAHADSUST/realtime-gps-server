package com.rls.gps.ping.location;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rls.gps.ping.config.PingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
