package com.rls.gps.history.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for history-service integration tests: the real application against a real MySQL, with
 * Consul switched off. Same skip-without-Docker arrangement as the other services.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.consul.enabled=false",
                "spring.cloud.consul.config.enabled=false",
                "spring.cloud.consul.discovery.enabled=false"
        })
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractHistoryServiceIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            // The consumer's insert is only fast with statement rewriting, so the tests exercise the
            // same driver behaviour production uses.
            .withUrlParam("rewriteBatchedStatements", "true")
            .withUrlParam("serverTimezone", "UTC");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void emptyHistory() {
        jdbc.update("DELETE FROM user_location");
    }

    protected long rowCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM user_location", Long.class);
        return count == null ? 0 : count;
    }
}
