package com.rls.gps.id.support;

import com.rls.gps.id.company.CompanyRepository;
import com.rls.gps.id.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
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
                "spring.cloud.consul.discovery.enabled=false",
                "gps.id.server-secret=" + AbstractIdServiceIT.SERVER_SECRET
        })
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIdServiceIT {

    /** Shared by every IT so they all reuse one Spring context. */
    public static final String SERVER_SECRET = "test-server-secret";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    /** Random, so a test run never collides with a locally running id-service on 9081. */
    protected static final int INTERNAL_PORT = TestSocketUtils.findAvailableTcpPort();

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("gps.id.internal-port", () -> INTERNAL_PORT);
    }

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected CompanyRepository companyRepository;

    /**
     * Every test starts from an empty database. Users go first: they reference companies, so the
     * reverse order would trip the foreign key.
     */
    @BeforeEach
    void resetDatabase() {
        userRepository.deleteAllInBatch();
        companyRepository.deleteAllInBatch();
    }

    /** Absolute URL for an endpoint that is only served on the restricted port. */
    protected static String internalUrl(String path) {
        return "http://localhost:" + INTERNAL_PORT + path;
    }
}
