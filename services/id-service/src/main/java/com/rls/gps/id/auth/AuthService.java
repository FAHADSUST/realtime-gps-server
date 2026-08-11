package com.rls.gps.id.auth;

import com.rls.gps.common.error.ApiException;
import com.rls.gps.common.error.ApiExceptions;
import com.rls.gps.id.auth.dto.TokenRequest;
import com.rls.gps.id.auth.dto.TokenResponse;
import com.rls.gps.id.company.Company;
import com.rls.gps.id.company.CompanyRepository;
import com.rls.gps.id.security.SecretVerifier;
import com.rls.gps.id.token.IssuedToken;
import com.rls.gps.id.token.TokenService;
import com.rls.gps.id.user.User;
import com.rls.gps.id.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exchanges user credentials for an access token.
 *
 * <p>Wrong app key, unknown username and wrong password all produce the same 401: telling them apart
 * would turn this endpoint into a directory of which companies and users exist.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final SecretVerifier secretVerifier;
    private final TokenService tokenService;

    public AuthService(CompanyRepository companyRepository,
                       UserRepository userRepository,
                       SecretVerifier secretVerifier,
                       TokenService tokenService) {
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.secretVerifier = secretVerifier;
        this.tokenService = tokenService;
    }

    @Transactional(readOnly = true)
    public TokenResponse issueToken(TokenRequest request) {
        Company company = companyRepository.findByAppKey(request.appKey()).orElse(null);
        if (company == null) {
            // Still pay for a comparison so a bad app key costs what a bad password costs.
            secretVerifier.matches(request.password(), null);
            throw invalidCredentials();
        }
        if (!company.isActive()) {
            throw ApiExceptions.forbidden("company_suspended", "This company is suspended");
        }

        User user = userRepository.findByCompanyIdAndUsername(company.getId(), request.username())
                .orElse(null);
        if (!secretVerifier.matches(request.password(), user == null ? null : user.getPasswordHash())) {
            log.warn("token_request_rejected appKey={} username={}", request.appKey(), request.username());
            throw invalidCredentials();
        }
        if (!user.isActive()) {
            throw ApiExceptions.forbidden("user_disabled", "This user is disabled");
        }

        IssuedToken issued = tokenService.issue(company.getId(), user.getId(), company.getAppKey());
        log.info("token_issued companyId={} userId={} expiresAt={}",
                company.getId(), user.getId(), issued.expiresAt());
        return TokenResponse.of(issued, company.getId(), user.getId());
    }

    private static ApiException invalidCredentials() {
        return ApiExceptions.unauthorized("invalid_credentials", "App key, username or password is wrong");
    }
}
