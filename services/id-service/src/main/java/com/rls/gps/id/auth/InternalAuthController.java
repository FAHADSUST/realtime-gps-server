package com.rls.gps.id.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.rls.gps.common.web.GpsHeaders;
import com.rls.gps.id.auth.dto.AuthenticateResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gateway's authentication hook, called by the {@code rls_auth} Kong plugin for every proxied
 * request. Served only on the internal port - a client that could call this directly could not gain
 * anything, but it has no business reaching it either.
 *
 * <p>The identity is returned both as headers (what the plugin copies upstream) and as a body (what
 * a human debugging with curl can read).
 */
@RestController
@RequestMapping("/api/v1/internal")
public class InternalAuthController {

    private final AuthService authService;
    private final Clock clock;

    public InternalAuthController(AuthService authService, Clock clock) {
        this.authService = authService;
        this.clock = clock;
    }

    @GetMapping("/authenticate")
    public AuthenticateResponse authenticate(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletResponse response) {

        AuthenticateResponse authenticated = authService.authenticate(authorization);

        response.setHeader(GpsHeaders.COMPANY_ID, authenticated.companyId());
        response.setHeader(GpsHeaders.USER_ID, authenticated.userId());
        if (authenticated.appKey() != null) {
            response.setHeader(GpsHeaders.APP_KEY, authenticated.appKey());
        }
        // Lets the plugin cache this decision for exactly the remaining life of the token.
        response.setHeader(GpsHeaders.TOKEN_EXPIRES_IN, Long.toString(secondsUntil(authenticated.expiresAt())));

        return authenticated;
    }

    private long secondsUntil(Instant expiresAt) {
        long seconds = Duration.between(clock.instant(), expiresAt).toSeconds();
        return Math.max(seconds, 0);
    }
}
