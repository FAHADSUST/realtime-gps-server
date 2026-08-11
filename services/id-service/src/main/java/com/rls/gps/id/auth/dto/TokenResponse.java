package com.rls.gps.id.auth.dto;

import java.time.Instant;

import com.rls.gps.id.token.IssuedToken;

public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        Instant expiresAt,
        String companyId,
        String userId) {

    public static TokenResponse of(IssuedToken issued, String companyId, String userId) {
        return new TokenResponse(issued.token(), "Bearer", issued.expiresIn(), issued.expiresAt(),
                companyId, userId);
    }
}
