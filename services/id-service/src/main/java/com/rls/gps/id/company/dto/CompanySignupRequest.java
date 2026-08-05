package com.rls.gps.id.company.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanySignupRequest(

        @NotBlank
        @Size(min = 2, max = 150)
        String name,

        @Email
        @Size(max = 255)
        String contactEmail) {
}
