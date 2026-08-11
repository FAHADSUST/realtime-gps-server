package com.rls.gps.id.config;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;

import javax.crypto.SecretKey;

import com.rls.gps.id.token.TokenService;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class TokenConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TokenConfiguration.class);

    /** HS256 needs a key at least as long as its output. */
    private static final int MIN_SECRET_BYTES = 32;

    @Bean
    public TokenService tokenService(IdProperties properties, Clock clock) {
        IdProperties.Jwt jwt = properties.jwt();
        return new TokenService(signingKey(jwt.secret()), jwt.issuer(), jwt.ttl(), clock);
    }

    private static SecretKey signingKey(String configuredSecret) {
        if (!StringUtils.hasText(configuredSecret)) {
            // A random key beats shipping a default one: a forgotten default signing key lets
            // anyone mint tokens, while a random key merely stops working, loudly.
            log.warn("jwt_secret_missing: gps.id.jwt.secret is not configured, generating a random key. "
                    + "Tokens will not survive a restart and will not validate on another instance.");
            byte[] random = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(random);
            return Keys.hmacShaKeyFor(random);
        }

        byte[] secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("gps.id.jwt.secret must be at least " + MIN_SECRET_BYTES
                    + " bytes for HS256, but is " + secret.length);
        }
        return Keys.hmacShaKeyFor(secret);
    }
}
