package com.rls.gps.id.user;

import com.rls.gps.id.company.Company;
import com.rls.gps.id.company.CompanyService;
import com.rls.gps.id.user.dto.UserResponse;
import com.rls.gps.id.user.dto.UserSignupRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public user endpoints, reachable through Kong.
 *
 * <p>Signup cannot require a user token - there is no user yet - so it authenticates with the
 * company's own app key and secret instead. Kong therefore exempts this route from {@code rls_auth}.
 */
@RestController
@RequestMapping("/api/v1/user")
public class UserController {

    private final CompanyService companyService;
    private final UserService userService;

    public UserController(CompanyService companyService, UserService userService) {
        this.companyService = companyService;
        this.userService = userService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse signup(@Valid @RequestBody UserSignupRequest request) {
        Company company = companyService.authenticate(request.appKey(), request.appSecret());
        return userService.register(company, request);
    }
}
