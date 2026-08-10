package com.rls.gps.id.user.dto;

import java.time.Instant;

import com.rls.gps.id.user.User;
import com.rls.gps.id.user.UserStatus;

/** A user as returned to clients - never carries the password hash. */
public record UserResponse(
        String userId,
        String companyId,
        String username,
        String displayName,
        UserStatus status,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getCompanyId(), user.getUsername(),
                user.getDisplayName(), user.getStatus(), user.getCreatedAt());
    }
}
