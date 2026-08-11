package com.rls.gps.id.token;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import javax.crypto.SecretKey;

import com.rls.gps.common.error.ApiException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {

    private static final String ISSUER = "rls-id-service";
    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final SecretKey KEY =
            Keys.hmacShaKeyFor("a-test-signing-secret-that-is-long-enough".getBytes(StandardCharsets.UTF_8));
    private static final SecretKey OTHER_KEY =
            Keys.hmacShaKeyFor("a-different-signing-secret-of-equal-size!".getBytes(StandardCharsets.UTF_8));

    private final TokenService tokens = serviceAt(NOW, KEY);

    @Test
    void issuedTokenCarriesTheCallerIdentity() {
        IssuedToken issued = tokens.issue("company-1", "user-9", "ak_abc");

        VerifiedToken verified = tokens.verify(issued.token());

        assertThat(verified.companyId()).isEqualTo("company-1");
        assertThat(verified.userId()).isEqualTo("user-9");
        assertThat(verified.appKey()).isEqualTo("ak_abc");
        assertThat(verified.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void reportsWhenItExpiresWithoutMakingTheClientParseTheToken() {
        IssuedToken issued = tokens.issue("company-1", "user-9", "ak_abc");

        assertThat(issued.issuedAt()).isEqualTo(NOW);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(issued.expiresIn()).isEqualTo(3600);
    }

    @Test
    void rejectsATokenOnceItHasExpired() {
        String token = tokens.issue("company-1", "user-9", "ak_abc").token();
        TokenService later = serviceAt(NOW.plus(Duration.ofHours(2)), KEY);

        assertThatThrownBy(() -> later.verify(token))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.code()).isEqualTo("token_expired");
                });
    }

    @Test
    void acceptsATokenThatIsStillWithinItsLifetime() {
        String token = tokens.issue("company-1", "user-9", "ak_abc").token();
        TokenService slightlyLater = serviceAt(NOW.plus(Duration.ofMinutes(59)), KEY);

        assertThat(slightlyLater.verify(token).userId()).isEqualTo("user-9");
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        String foreign = serviceAt(NOW, OTHER_KEY).issue("company-1", "user-9", "ak_abc").token();

        assertThatThrownBy(() -> tokens.verify(foreign))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("token_invalid"));
    }

    @Test
    void rejectsATamperedPayload() {
        String token = tokens.issue("company-1", "user-9", "ak_abc").token();
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];

        assertThatThrownBy(() -> tokens.verify(tampered)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsAnUnsignedTokenClaimingTheSameIdentity() {
        // The classic "alg: none" forgery - a well-formed JWT with no signature at all.
        String unsigned = Jwts.builder()
                .issuer(ISSUER)
                .subject("user-9")
                .claim("cid", "company-1")
                .expiration(Date.from(NOW.plus(Duration.ofHours(1))))
                .compact();

        assertThatThrownBy(() -> tokens.verify(unsigned))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("token_invalid"));
    }

    @Test
    void rejectsATokenFromAnotherIssuer() {
        String foreignIssuer = new TokenService(KEY, "someone-else", Duration.ofHours(1), fixed(NOW))
                .issue("company-1", "user-9", "ak_abc").token();

        assertThatThrownBy(() -> tokens.verify(foreignIssuer))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("token_invalid"));
    }

    @Test
    void rejectsGarbageAndEmptyInput() {
        assertThatThrownBy(() -> tokens.verify(null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("token_missing"));
        assertThatThrownBy(() -> tokens.verify("   "))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("token_missing"));
        assertThatThrownBy(() -> tokens.verify("not-a-jwt"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("token_invalid"));
    }

    private static TokenService serviceAt(Instant now, SecretKey key) {
        return new TokenService(key, ISSUER, Duration.ofHours(1), fixed(now));
    }

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
