package com.rls.gps.id.company;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.rls.gps.id.support.AbstractIdServiceIT;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanyRepositoryIT extends AbstractIdServiceIT {

    @Test
    void storesAndReadsBackACompany() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Company company = Company.register("Acme Logistics", "ops@acme.example", "ak_one", "hash", now);

        companyRepository.saveAndFlush(company);

        Company loaded = companyRepository.findByAppKey("ak_one").orElseThrow();
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
        companyRepository.saveAndFlush(Company.register("First", null, "ak_dup", "hash", now));

        assertThatThrownBy(() ->
                companyRepository.saveAndFlush(Company.register("Second", null, "ak_dup", "hash", now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void companyNameIsUniqueAndCaseInsensitive() {
        Instant now = Instant.now();
        companyRepository.saveAndFlush(Company.register("Acme Logistics", null, "ak_a", "hash", now));

        assertThat(companyRepository.existsByNameIgnoreCase("acme logistics")).isTrue();
        assertThat(companyRepository.existsByNameIgnoreCase("ACME LOGISTICS")).isTrue();
        assertThat(companyRepository.existsByNameIgnoreCase("Other")).isFalse();

        // utf8mb4_0900_ai_ci makes the unique index itself case-insensitive.
        assertThatThrownBy(() ->
                companyRepository.saveAndFlush(Company.register("acme logistics", null, "ak_b", "hash", now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownAppKeyReturnsEmpty() {
        assertThat(companyRepository.findByAppKey("ak_missing")).isEmpty();
    }
}
