package com.rls.gps.id.token;

import java.time.Instant;

/**
 * @param token     the signed JWT
 * @param expiresAt when it stops being accepted
 * @param expiresIn seconds until expiry, so a client need not parse the token to schedule a refresh
 */
public record IssuedToken(String token, Instant issuedAt, Instant expiresAt, long expiresIn) {
}
