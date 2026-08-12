package com.rls.gps.id.auth.dto;

import java.time.Instant;

/**
 * What the gateway learns about a caller. {@code expiresAt} lets the plugin cache the decision for
 * exactly as long as the token is valid, and no longer.
 */
public record AuthenticateResponse(String companyId, String userId, String appKey, Instant expiresAt) {
}
