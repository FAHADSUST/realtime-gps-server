package com.rls.gps.id.company;

import com.rls.gps.common.web.GpsHeaders;
import com.rls.gps.id.company.dto.CompanySignupRequest;
import com.rls.gps.id.company.dto.CompanySignupResponse;
import com.rls.gps.id.security.ServerSecretGuard;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Restricted company administration. Served only on the internal port - the original spec states
 * this endpoint "will not be provided to any person outside of the Realtime Location Service".
 */
@RestController
@RequestMapping("/api/v1/company")
public class CompanyController {

    private final CompanyService companyService;
    private final ServerSecretGuard serverSecretGuard;

    public CompanyController(CompanyService companyService, ServerSecretGuard serverSecretGuard) {
        this.companyService = companyService;
        this.serverSecretGuard = serverSecretGuard;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public CompanySignupResponse signup(
            @RequestHeader(name = GpsHeaders.SERVER_SECRET, required = false) String serverSecret,
            @Valid @RequestBody CompanySignupRequest request) {
        serverSecretGuard.verify(serverSecret);
        return companyService.register(request);
    }
}
