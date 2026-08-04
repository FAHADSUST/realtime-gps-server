package com.rls.gps.id.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.rls.gps.common.error.ApiException;
import com.rls.gps.common.error.ApiExceptions;
import com.rls.gps.id.config.IdProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Guards the restricted company-registration endpoint with the {@code sret} header from the spec.
 *
 * <p>Fails closed: if no secret is configured the endpoint refuses every request instead of
 * accepting all of them.
 */
@Component
public class ServerSecretGuard {

    private static final Logger log = LoggerFactory.getLogger(ServerSecretGuard.class);

    private final String serverSecret;

    public ServerSecretGuard(IdProperties properties) {
        this.serverSecret = properties.serverSecret();
    }

    @PostConstruct
    void warnIfUnconfigured() {
        if (!StringUtils.hasText(serverSecret)) {
            log.error("server_secret_missing: gps.id.server-secret is not configured; "
                    + "company registration will reject every request");
        }
    }

    public void verify(String presented) {
        if (!StringUtils.hasText(serverSecret)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "server_secret_not_configured",
                    "Company registration is not available on this deployment");
        }
        if (!StringUtils.hasText(presented) || !constantTimeEquals(presented, serverSecret)) {
            log.warn("server_secret_rejected presented={}", StringUtils.hasText(presented) ? "yes" : "no");
            throw ApiExceptions.forbidden("invalid_server_secret", "A valid sret header is required");
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
