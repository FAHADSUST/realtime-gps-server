package com.rls.gps.id.token;

import java.time.Instant;

/** What the gateway needs to know about a caller once a token checks out. */
public record VerifiedToken(String companyId, String userId, String appKey, Instant expiresAt) {
}
