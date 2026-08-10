package com.rls.gps.id.user;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByCompanyIdAndUsername(String companyId, String username);

    /** Every read is company-scoped: there is no "find by id" that could cross a tenant boundary. */
    Optional<User> findByIdAndCompanyId(String id, String companyId);

    Page<User> findByCompanyIdOrderByUsernameAsc(String companyId, Pageable pageable);

    boolean existsByCompanyIdAndUsername(String companyId, String username);
}
