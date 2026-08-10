package com.rls.gps.id.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Company credentials travel in the body rather than in headers: {@code X-App-Key} is an identity
 * header the gateway owns and overwrites, so a client cannot use it to present credentials.
 */
public record UserSignupRequest(

        @NotBlank
        @Size(max = 64)
        String appKey,

        @NotBlank
        @Size(max = 64)
        String appSecret,

        @NotBlank
        @Size(min = 3, max = 150)
        @Pattern(regexp = "[A-Za-z0-9._@-]+",
                message = "may contain letters, digits and . _ @ - only")
        String username,

        @NotBlank
        @Size(min = 8, max = 72)
        String password,

        @Size(max = 150)
        String displayName) {
}
