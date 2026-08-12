package com.rls.gps.id.auth;

import com.rls.gps.common.error.ApiException;
import com.rls.gps.common.error.ApiExceptions;
import com.rls.gps.id.auth.dto.AuthenticateResponse;
import com.rls.gps.id.auth.dto.TokenRequest;
import com.rls.gps.id.auth.dto.TokenResponse;
import com.rls.gps.id.company.Company;
import com.rls.gps.id.company.CompanyRepository;
import com.rls.gps.id.security.SecretVerifier;
import com.rls.gps.id.token.IssuedToken;
import com.rls.gps.id.token.TokenService;
import com.rls.gps.id.token.VerifiedToken;
import com.rls.gps.id.user.User;
import com.rls.gps.id.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Exchanges user credentials for an access token.
 *
 * <p>Wrong app key, unknown username and wrong password all produce the same 401: telling them apart
 * would turn this endpoint into a directory of which companies and users exist.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String BEARER_PREFIX = "Bearer ";

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

    /**
     * Verifies the token the gateway received and re-checks the caller still exists and is allowed
     * in.
     *
     * <p>The database lookup is deliberate: a token is valid until it expires, so without it a
     * disabled or deleted user would keep working for up to an hour. The gateway caches the result
     * until the token expires, so this costs one query per token, not per request.
     */
    @Transactional(readOnly = true)
    public AuthenticateResponse authenticate(String authorizationHeader) {
        VerifiedToken token = tokenService.verify(bearerToken(authorizationHeader));

        User user = userRepository.findByIdAndCompanyId(token.userId(), token.companyId())
                .orElseThrow(() -> {
                    log.warn("token_for_unknown_user companyId={} userId={}",
                            token.companyId(), token.userId());
                    // Deliberately not "user not found": the token is simply no longer usable.
                    return ApiExceptions.unauthorized("token_invalid", "Access token is not valid");
                });
        if (!user.isActive()) {
            throw ApiExceptions.forbidden("user_disabled", "This user is disabled");
        }

        Company company = companyRepository.findById(token.companyId())
                .orElseThrow(() -> ApiExceptions.unauthorized("token_invalid", "Access token is not valid"));
        if (!company.isActive()) {
            throw ApiExceptions.forbidden("company_suspended", "This company is suspended");
        }

        return new AuthenticateResponse(company.getId(), user.getId(), company.getAppKey(),
                token.expiresAt());
    }

    /** Accepts {@code Bearer <token>}; the scheme is case-insensitive per RFC 7235. */
    private static String bearerToken(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) {
            throw ApiExceptions.unauthorized("token_missing", "No access token was presented");
        }
        String header = authorizationHeader.trim();
        if (header.length() < BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw ApiExceptions.unauthorized("token_missing", "Expected an Authorization: Bearer header");
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    private static ApiException invalidCredentials() {
        return ApiExceptions.unauthorized("invalid_credentials", "App key, username or password is wrong");
    }
}
