package com.rls.gps.id.user;

import com.rls.gps.common.error.ApiExceptions;
import com.rls.gps.common.security.CurrentIdentity;
import com.rls.gps.common.security.Identity;
import com.rls.gps.id.company.Company;
import com.rls.gps.id.company.CompanyService;
import com.rls.gps.id.user.dto.PagedUsersResponse;
import com.rls.gps.id.user.dto.UserResponse;
import com.rls.gps.id.user.dto.UserSignupRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
@Validated
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

    /**
     * Lists the caller's company users.
     *
     * <p>The company comes from the verified identity, never from the query string, so no app key
     * can be used to read another tenant's users. {@code appKey} is accepted because the spec names
     * it, but it is only checked for agreement with the caller.
     */
    @GetMapping("/resolve")
    public PagedUsersResponse resolve(@CurrentIdentity Identity caller,
                                      @RequestParam(required = false) String appKey,
                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        if (StringUtils.hasText(appKey) && !appKey.equals(caller.appKey())) {
            throw ApiExceptions.forbidden("app_key_mismatch",
                    "The requested app key does not belong to the authenticated company");
        }
        return userService.resolve(caller.companyId(), page, size);
    }
}
