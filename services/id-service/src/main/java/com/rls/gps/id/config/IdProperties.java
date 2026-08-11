package com.rls.gps.id.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Id service configuration. Secrets come from Consul KV ({@code config/id-service/data}).
 *
 * @param internalPort extra HTTP port that serves the restricted endpoints; Kong never sees it
 * @param serverSecret value the {@code sret} header must carry to register a company
 * @param jwt          access-token settings
 */
@ConfigurationProperties(prefix = "gps.id")
public record IdProperties(@DefaultValue("9081") int internalPort,
                           String serverSecret,
                           @DefaultValue Jwt jwt) {

    /**
     * @param secret HMAC signing key, at least 32 bytes. Left blank, a random key is generated at
     *               startup - fine for a single local instance, useless across a restart or a second
     *               replica, which is why it is loud about it.
     * @param issuer {@code iss} claim, checked on every verification
     * @param ttl    how long an issued token stays valid
     */
    public record Jwt(String secret,
                      @DefaultValue("rls-id-service") String issuer,
                      @DefaultValue("1h") Duration ttl) {
    }
}
