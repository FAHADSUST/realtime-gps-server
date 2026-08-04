package com.rls.gps.id.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Id service configuration. Secrets come from Consul KV ({@code config/id-service/data}).
 *
 * @param internalPort extra HTTP port that serves the restricted endpoints; Kong never sees it
 * @param serverSecret value the {@code sret} header must carry to register a company
 */
@ConfigurationProperties(prefix = "gps.id")
public record IdProperties(@DefaultValue("9081") int internalPort, String serverSecret) {
}
