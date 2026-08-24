package com.rls.gps.ping.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ping service tuning. Values come from Consul KV ({@code config/ping-service/data}).
 */
@ConfigurationProperties(prefix = "gps.ping")
public record PingProperties(@DefaultValue Redis redis) {

    /**
     * @param keyPrefix       namespace for every key this service writes
     * @param lastLocationTtl how long a silent user's last position stays readable. Without a TTL a
     *                        device that stopped reporting months ago still answers radius queries
     *                        as though it were there.
     */
    public record Redis(@DefaultValue("gps") String keyPrefix,
                        @DefaultValue("24h") Duration lastLocationTtl) {
    }
}
