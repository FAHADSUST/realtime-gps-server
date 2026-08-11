package com.rls.gps.id.security;

import com.rls.gps.common.error.ApiException;
import com.rls.gps.id.config.IdProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerSecretGuardTest {

    @Test
    void acceptsTheConfiguredSecret() {
        ServerSecretGuard guard = guardWith("the-secret");

        assertThatCode(() -> guard.verify("the-secret")).doesNotThrowAnyException();
    }

    @Test
    void rejectsAWrongSecret() {
        ServerSecretGuard guard = guardWith("the-secret");

        assertThatThrownBy(() -> guard.verify("not-the-secret"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void rejectsAMissingSecret() {
        ServerSecretGuard guard = guardWith("the-secret");

        assertThatThrownBy(() -> guard.verify(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.verify("")).isInstanceOf(ApiException.class);
    }

    @Test
    void failsClosedWhenNoSecretIsConfigured() {
        ServerSecretGuard guard = guardWith(null);

        assertThatThrownBy(() -> guard.verify("anything"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    org.assertj.core.api.Assertions.assertThat(ex.status())
                            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    org.assertj.core.api.Assertions.assertThat(ex.code())
                            .isEqualTo("server_secret_not_configured");
                });
    }

    private static ServerSecretGuard guardWith(String secret) {
        return new ServerSecretGuard(new IdProperties(9081, secret,
                new IdProperties.Jwt(null, "rls-id-service", java.time.Duration.ofHours(1))));
    }
}
