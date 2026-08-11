package com.rls.gps.id.token;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.rls.gps.common.error.ApiExceptions;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Issues and verifies the access tokens clients present to the gateway.
 *
 * <p>HS256: the Id service is both the only issuer and the only verifier, so a shared secret is
 * enough and avoids handing every service a public key to fetch. Claims are deliberately minimal -
 * company, user and app key - because everything else can go stale between issue and use.
 */
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    static final String CLAIM_COMPANY_ID = "cid";
    static final String CLAIM_APP_KEY = "ak";

    private final SecretKey key;
    private final String issuer;
    private final Duration ttl;
    private final Clock clock;

    public TokenService(SecretKey key, String issuer, Duration ttl, Clock clock) {
        this.key = key;
        this.issuer = issuer;
        this.ttl = ttl;
        this.clock = clock;
    }

    public IssuedToken issue(String companyId, String userId, String appKey) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(ttl);

        String token = Jwts.builder()
                .issuer(issuer)
                .subject(userId)
                .claim(CLAIM_COMPANY_ID, companyId)
                .claim(CLAIM_APP_KEY, appKey)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        return new IssuedToken(token, issuedAt, expiresAt, ttl.toSeconds());
    }

    /**
     * @throws com.rls.gps.common.error.ApiException 401 for anything that is not a currently valid
     *                                               token signed by this service
     */
    public VerifiedToken verify(String token) {
        if (!StringUtils.hasText(token)) {
            throw ApiExceptions.unauthorized("token_missing", "No access token was presented");
        }

        try {
            // parseSignedClaims rejects unsigned tokens outright, so "alg": "none" cannot slip past.
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String companyId = claims.get(CLAIM_COMPANY_ID, String.class);
            String userId = claims.getSubject();
            if (!StringUtils.hasText(companyId) || !StringUtils.hasText(userId)) {
                throw ApiExceptions.unauthorized("token_invalid", "Access token is missing required claims");
            }

            return new VerifiedToken(companyId, userId, claims.get(CLAIM_APP_KEY, String.class),
                    claims.getExpiration().toInstant());
        } catch (ExpiredJwtException ex) {
            throw ApiExceptions.unauthorized("token_expired", "Access token has expired");
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("token_rejected reason={}", ex.getClass().getSimpleName());
            throw ApiExceptions.unauthorized("token_invalid", "Access token is not valid");
        }
    }
}
