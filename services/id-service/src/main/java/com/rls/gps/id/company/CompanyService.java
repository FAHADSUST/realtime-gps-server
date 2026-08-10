package com.rls.gps.id.company;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.rls.gps.common.error.ApiException;
import com.rls.gps.common.error.ApiExceptions;
import com.rls.gps.id.company.dto.CompanySignupRequest;
import com.rls.gps.id.company.dto.CompanySignupResponse;
import com.rls.gps.id.security.CredentialGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyService {

    private static final Logger log = LoggerFactory.getLogger(CompanyService.class);

    private final CompanyRepository repository;
    private final CredentialGenerator credentialGenerator;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /** Hashed once at startup so a lookup miss can still spend a comparable amount of time. */
    private final String decoyHash;

    public CompanyService(CompanyRepository repository,
                          CredentialGenerator credentialGenerator,
                          PasswordEncoder passwordEncoder,
                          Clock clock) {
        this.repository = repository;
        this.credentialGenerator = credentialGenerator;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.decoyHash = passwordEncoder.encode(credentialGenerator.appSecret());
    }

    /**
     * Registers a company and returns its credentials. The app secret is present in the response and
     * nowhere else - only its BCrypt hash is persisted.
     */
    @Transactional
    public CompanySignupResponse register(CompanySignupRequest request) {
        String name = request.name().trim();
        if (repository.existsByNameIgnoreCase(name)) {
            throw ApiExceptions.conflict("company_already_exists", "A company named '" + name + "' already exists");
        }

        String appKey = credentialGenerator.appKey();
        String appSecret = credentialGenerator.appSecret();
        Instant now = clock.instant();

        Company company = Company.register(name, request.contactEmail(), appKey,
                passwordEncoder.encode(appSecret), now);

        try {
            repository.saveAndFlush(company);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent signup with the same name, or the (astronomically unlikely) app key clash.
            throw ApiExceptions.conflict("company_already_exists", "A company named '" + name + "' already exists");
        }

        log.info("company_registered companyId={} appKey={}", company.getId(), appKey);
        return new CompanySignupResponse(company.getId(), company.getName(), company.getContactEmail(),
                appKey, appSecret, company.getStatus(), company.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public Company requireActiveByAppKey(String appKey) {
        Company company = repository.findByAppKey(appKey)
                .orElseThrow(() -> ApiExceptions.notFound("company_not_found", "Unknown app key"));
        if (!company.isActive()) {
            throw ApiExceptions.forbidden("company_suspended", "This company is suspended");
        }
        return company;
    }

    /**
     * Verifies an app key and secret pair.
     *
     * <p>An unknown key and a wrong secret produce the same error, and an unknown key still pays for
     * a BCrypt comparison: otherwise the status code or the response time would tell an attacker
     * which app keys exist.
     */
    @Transactional(readOnly = true)
    public Company authenticate(String appKey, String appSecret) {
        Optional<Company> found = repository.findByAppKey(appKey);
        if (found.isEmpty()) {
            passwordEncoder.matches(appSecret, decoyHash);
            throw invalidCredentials();
        }

        Company company = found.get();
        if (!passwordEncoder.matches(appSecret, company.getAppSecretHash())) {
            log.warn("company_credentials_rejected appKey={}", appKey);
            throw invalidCredentials();
        }
        if (!company.isActive()) {
            throw ApiExceptions.forbidden("company_suspended", "This company is suspended");
        }
        return company;
    }

    private static ApiException invalidCredentials() {
        return ApiExceptions.unauthorized("invalid_company_credentials", "Unknown app key or secret");
    }
}
