package com.rls.gps.id.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for id-service integration tests: the real application on a random port, talking to a
 * real MySQL, with Consul switched off.
 *
 * <p>The container is {@code static}, so every subclass shares one MySQL for the whole test run.
 *
 * <p>{@code disabledWithoutDocker} makes these tests skip rather than fail where no Docker daemon is
 * reachable, so {@code mvn verify} stays truthful on a developer machine without Docker while CI
 * still runs them for real. The datasource is wired through {@link DynamicPropertySource} rather
 * than {@code @ServiceConnection} because the latter resolves the image while the test context is
 * being built - before JUnit evaluates the skip condition - which turns a skip into an error.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.consul.enabled=false",
                "spring.cloud.consul.config.enabled=false",
                "spring.cloud.consul.discovery.enabled=false"
        })
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIdServiceIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
