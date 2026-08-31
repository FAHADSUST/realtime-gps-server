package com.rls.gps.ping.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ping service tuning. Values come from Consul KV ({@code config/ping-service/data}).
 */
@ConfigurationProperties(prefix = "gps.ping")
public record PingProperties(@DefaultValue Redis redis,
                             @DefaultValue Buffer buffer,
                             @DefaultValue("5m") Duration maxClockSkew) {

    /**
     * The in-memory staging area between a request and the broker.
     *
     * @param capacity        how many fixes may wait to be published. Bounded on purpose: an
     *                        unbounded queue turns a slow broker into an out-of-memory kill.
     * @param maxBatchSize    fixes per published message
     * @param flushInterval   how long a lone fix may wait for company before being published anyway
     * @param shutdownTimeout how long shutdown waits for the flush loop to finish its current batch
     *                        before draining the rest
     */
    public record Buffer(@DefaultValue("50000") int capacity,
                         @DefaultValue("500") int maxBatchSize,
                         @DefaultValue("500ms") Duration flushInterval,
                         @DefaultValue("5s") Duration shutdownTimeout) {
    }

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
