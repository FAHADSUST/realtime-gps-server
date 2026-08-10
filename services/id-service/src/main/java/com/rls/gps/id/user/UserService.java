package com.rls.gps.id.user;

import java.time.Clock;

import com.rls.gps.common.error.ApiExceptions;
import com.rls.gps.id.company.Company;
import com.rls.gps.id.user.dto.UserResponse;
import com.rls.gps.id.user.dto.UserSignupRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(UserRepository repository, PasswordEncoder passwordEncoder, Clock clock) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public UserResponse register(Company company, UserSignupRequest request) {
        String username = request.username().trim();
        if (repository.existsByCompanyIdAndUsername(company.getId(), username)) {
            throw usernameTaken(username);
        }

        User user = User.register(company.getId(), username, request.displayName(),
                passwordEncoder.encode(request.password()), clock.instant());

        try {
            repository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Two signups for the same username raced past the check above.
            throw usernameTaken(username);
        }

        log.info("user_registered companyId={} userId={} username={}",
                company.getId(), user.getId(), username);
        return UserResponse.from(user);
    }

    private static com.rls.gps.common.error.ApiException usernameTaken(String username) {
        return ApiExceptions.conflict("user_already_exists",
                "A user named '" + username + "' already exists for this company");
    }
}
