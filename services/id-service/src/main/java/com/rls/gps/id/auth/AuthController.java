package com.rls.gps.id.auth;

import com.rls.gps.id.auth.dto.TokenRequest;
import com.rls.gps.id.auth.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token issuance.
 *
 * <p>Not in the original specification, which describes how the gateway validates a token but never
 * how a client obtains one. Without this endpoint nothing else in the platform is callable.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        return authService.issueToken(request);
    }
}
