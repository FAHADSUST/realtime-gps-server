package com.rls.gps.id.company;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.rls.gps.id.support.AbstractIdServiceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanyRepositoryIT extends AbstractIdServiceIT {

    @Autowired
    private CompanyRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void storesAndReadsBackACompany() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Company company = Company.register("Acme Logistics", "ops@acme.example", "ak_one", "hash", now);

        repository.saveAndFlush(company);

        Company loaded = repository.findByAppKey("ak_one").orElseThrow();
        assertThat(loaded.getId()).isEqualTo(company.getId());
        assertThat(loaded.getName()).isEqualTo("Acme Logistics");
        assertThat(loaded.getContactEmail()).isEqualTo("ops@acme.example");
        assertThat(loaded.getStatus()).isEqualTo(CompanyStatus.ACTIVE);
        assertThat(loaded.isActive()).isTrue();
        assertThat(loaded.getCreatedAt()).isCloseTo(now, org.assertj.core.api.Assertions.within(1, ChronoUnit.MILLIS));
    }

    @Test
    void appKeyIsUnique() {
        Instant now = Instant.now();
        repository.saveAndFlush(Company.register("First", null, "ak_dup", "hash", now));

        assertThatThrownBy(() ->
                repository.saveAndFlush(Company.register("Second", null, "ak_dup", "hash", now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void companyNameIsUniqueAndCaseInsensitive() {
        Instant now = Instant.now();
        repository.saveAndFlush(Company.register("Acme Logistics", null, "ak_a", "hash", now));

        assertThat(repository.existsByNameIgnoreCase("acme logistics")).isTrue();
        assertThat(repository.existsByNameIgnoreCase("ACME LOGISTICS")).isTrue();
        assertThat(repository.existsByNameIgnoreCase("Other")).isFalse();

        // utf8mb4_0900_ai_ci makes the unique index itself case-insensitive.
        assertThatThrownBy(() ->
                repository.saveAndFlush(Company.register("acme logistics", null, "ak_b", "hash", now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownAppKeyReturnsEmpty() {
        assertThat(repository.findByAppKey("ak_missing")).isEmpty();
    }
}
