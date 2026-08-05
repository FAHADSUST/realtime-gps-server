package com.rls.gps.id.security;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialGeneratorTest {

    private final CredentialGenerator generator = new CredentialGenerator();

    @Test
    void appKeyIsPrefixedAndUrlSafe() {
        String appKey = generator.appKey();

        assertThat(appKey).startsWith(CredentialGenerator.APP_KEY_PREFIX);
        assertThat(appKey).matches("ak_[A-Za-z0-9_-]{24}");
    }

    @Test
    void appSecretIsPrefixedAndLongEnough() {
        String appSecret = generator.appSecret();

        assertThat(appSecret).startsWith(CredentialGenerator.APP_SECRET_PREFIX);
        assertThat(appSecret).matches("as_[A-Za-z0-9_-]{43}");
        // BCrypt silently ignores anything past 72 bytes.
        assertThat(appSecret.length()).isLessThanOrEqualTo(72);
    }

    @Test
    void generatedValuesDoNotRepeat() {
        Set<String> keys = new HashSet<>();
        Set<String> secrets = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            keys.add(generator.appKey());
            secrets.add(generator.appSecret());
        }

        assertThat(keys).hasSize(500);
        assertThat(secrets).hasSize(500);
    }
}
