package com.rls.gps.id.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Id service configuration. Values come from Consul KV ({@code config/id-service/data}).
 *
 * @param internalPort extra HTTP port that serves the restricted endpoints; Kong never sees it
 */
@ConfigurationProperties(prefix = "gps.id")
public record IdProperties(@DefaultValue("9081") int internalPort) {
}
