package com.rls.gps.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityTest {

    @Test
    void isCompleteRequiresCompanyAndUser() {
        assertThat(new Identity("c1", "u1", "ak_1").isComplete()).isTrue();
        assertThat(new Identity("c1", "u1", null).isComplete()).isTrue();
        assertThat(new Identity("c1", null, "ak_1").isComplete()).isFalse();
        assertThat(new Identity(null, "u1", "ak_1").isComplete()).isFalse();
    }

    @Test
    void blankValuesDoNotCountAsPresent() {
        assertThat(new Identity("  ", "u1", null).isComplete()).isFalse();
        assertThat(new Identity("c1", "", null).isComplete()).isFalse();
    }

    @Test
    void anonymousMeansNeitherCompanyNorUser() {
        assertThat(new Identity(null, null, null).isAnonymous()).isTrue();
        assertThat(new Identity(null, "u1", null).isAnonymous()).isFalse();
    }
}
