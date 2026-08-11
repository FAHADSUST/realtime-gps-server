package com.rls.gps.id.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The app key identifies the company, because usernames are only unique within one. The company's
 * <em>secret</em> is deliberately not required: this call is made by end-user clients, which cannot
 * keep one.
 */
public record TokenRequest(

        @NotBlank
        @Size(max = 64)
        String appKey,

        @NotBlank
        @Size(max = 150)
        String username,

        @NotBlank
        @Size(max = 72)
        String password) {
}
