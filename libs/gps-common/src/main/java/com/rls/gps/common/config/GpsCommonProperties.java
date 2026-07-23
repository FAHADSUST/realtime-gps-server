package com.rls.gps.common.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Shared configuration for every GPS service. Values normally come from Consul KV
 * ({@code config/application/data} or {@code config/<service>/data}).
 */
@ConfigurationProperties(prefix = "gps.common")
public record GpsCommonProperties(@DefaultValue Gateway gateway) {

    /**
     * @param token     shared secret Kong must present; when blank the check is disabled
     * @param skipPaths paths exempt from the gateway check (health checks, docs)
     */
    public record Gateway(
            String token,
            @DefaultValue({"/actuator/**", "/api/v1/ping", "/v3/api-docs/**", "/swagger-ui/**", "/error"})
            List<String> skipPaths) {
    }
}
