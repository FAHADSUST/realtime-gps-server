package com.rls.gps.id.security;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Compares a presented secret against a stored hash without revealing whether the record exists.
 *
 * <p>The obvious implementation returns early when the lookup missed, and then "unknown app key"
 * answers in microseconds while "wrong secret" takes a full BCrypt round. That difference is enough
 * to enumerate valid keys and usernames, so a miss is charged for a comparison against a decoy hash
 * instead.
 */
@Component
public class SecretVerifier {

    private final PasswordEncoder passwordEncoder;
    private final String decoyHash;

    public SecretVerifier(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.decoyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * @param storedHash the hash to check against, or {@code null} when no record was found
     * @return true only when a record existed and the secret matched
     */
    public boolean matches(String presented, String storedHash) {
        String candidate = presented == null ? "" : presented;
        if (!StringUtils.hasText(storedHash)) {
            passwordEncoder.matches(candidate, decoyHash);
            return false;
        }
        return passwordEncoder.matches(candidate, storedHash);
    }
}
