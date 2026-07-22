package com.rls.gps.common.testapp;

import com.rls.gps.common.security.CurrentIdentity;
import com.rls.gps.common.security.Identity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints that echo back whatever identity the gateway established. */
@RestController
public class IdentityTestController {

    @GetMapping("/api/v1/whoami")
    public Identity whoami(@CurrentIdentity Identity identity) {
        return identity;
    }

    @GetMapping("/api/v1/whoami-optional")
    public Identity whoamiOptional(@CurrentIdentity(required = false) Identity identity) {
        return identity;
    }
}
