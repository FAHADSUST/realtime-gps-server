package com.rls.gps.id.company.dto;

import java.time.Instant;

import com.rls.gps.id.company.CompanyStatus;

/**
 * @param appSecret plaintext secret, returned only by the signup call - it cannot be recovered later
 */
public record CompanySignupResponse(
        String companyId,
        String name,
        String contactEmail,
        String appKey,
        String appSecret,
        CompanyStatus status,
        Instant createdAt) {
}
