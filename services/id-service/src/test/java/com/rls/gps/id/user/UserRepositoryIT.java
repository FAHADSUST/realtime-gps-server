package com.rls.gps.id.user;

import java.time.Instant;

import com.rls.gps.id.company.Company;
import com.rls.gps.id.support.AbstractIdServiceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRepositoryIT extends AbstractIdServiceIT {

    private String companyA;
    private String companyB;

    @BeforeEach
    void seedCompanies() {
        Instant now = Instant.now();
        companyA = companyRepository.saveAndFlush(
                Company.register("Company A", null, "ak_a", "hash", now)).getId();
        companyB = companyRepository.saveAndFlush(
                Company.register("Company B", null, "ak_b", "hash", now)).getId();
    }

    @Test
    void storesAndReadsBackAUser() {
        User saved = userRepository.saveAndFlush(
                User.register(companyA, "driver-1", "Driver One", "hash", Instant.now()));

        User loaded = userRepository.findByCompanyIdAndUsername(companyA, "driver-1").orElseThrow();
        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getDisplayName()).isEqualTo("Driver One");
        assertThat(loaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(loaded.isActive()).isTrue();
    }

    @Test
    void usernamesAreUniquePerCompanyNotGlobally() {
        Instant now = Instant.now();
        userRepository.saveAndFlush(User.register(companyA, "driver-1", null, "hash", now));

        // The same username in another company is fine...
        assertThat(userRepository.saveAndFlush(User.register(companyB, "driver-1", null, "hash", now)).getId())
                .isNotBlank();

        // ...but a duplicate within the same company is refused by the database.
        assertThatThrownBy(() ->
                userRepository.saveAndFlush(User.register(companyA, "driver-1", null, "hash", now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lookupsCannotCrossACompanyBoundary() {
        User user = userRepository.saveAndFlush(
                User.register(companyA, "driver-1", null, "hash", Instant.now()));

        assertThat(userRepository.findByIdAndCompanyId(user.getId(), companyA)).isPresent();
        assertThat(userRepository.findByIdAndCompanyId(user.getId(), companyB)).isEmpty();
        assertThat(userRepository.findByCompanyIdAndUsername(companyB, "driver-1")).isEmpty();
    }

    @Test
    void listsOneCompanysUsersInPagesOrderedByUsername() {
        Instant now = Instant.now();
        userRepository.saveAndFlush(User.register(companyA, "charlie", null, "hash", now));
        userRepository.saveAndFlush(User.register(companyA, "alice", null, "hash", now));
        userRepository.saveAndFlush(User.register(companyA, "bob", null, "hash", now));
        userRepository.saveAndFlush(User.register(companyB, "zoe", null, "hash", now));

        var firstPage = userRepository.findByCompanyIdOrderByUsernameAsc(companyA, PageRequest.of(0, 2));

        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getContent()).extracting(User::getUsername).containsExactly("alice", "bob");

        var secondPage = userRepository.findByCompanyIdOrderByUsernameAsc(companyA, PageRequest.of(1, 2));
        assertThat(secondPage.getContent()).extracting(User::getUsername).containsExactly("charlie");
    }

    @Test
    void existsIsCompanyScoped() {
        userRepository.saveAndFlush(User.register(companyA, "driver-1", null, "hash", Instant.now()));

        assertThat(userRepository.existsByCompanyIdAndUsername(companyA, "driver-1")).isTrue();
        assertThat(userRepository.existsByCompanyIdAndUsername(companyB, "driver-1")).isFalse();
    }
}
