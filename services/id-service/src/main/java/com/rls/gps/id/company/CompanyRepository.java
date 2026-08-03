package com.rls.gps.id.company;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, String> {

    Optional<Company> findByAppKey(String appKey);

    boolean existsByNameIgnoreCase(String name);
}
