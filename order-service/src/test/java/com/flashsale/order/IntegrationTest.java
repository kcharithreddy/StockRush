package com.flashsale.order;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.util.UUID;

/**
 * Base for tests that need real PostgreSQL 16 (with infra/schema.sql) and Redis 7.
 * The containers start once and are shared by every test class.
 * Kafka listeners and the scheduled jobs are off: tests call the beans directly.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "flashsale.jobs.enabled=false",
        "flashsale.admin.reset-enabled=true",
        "spring.data.redis.password=",
        com.flashsale.order.security.TestTokens.SECRET_PROPERTY
})
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.16")
            .withDatabaseName("flashsale")
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/schema.sql"),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.12-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void cleanState() {
        jdbc.update("TRUNCATE orders");
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    protected UUID insertOrder(long saleId, UUID userId, String status, int ageMinutes) {
        UUID token = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (order_token, sale_id, user_id, status, created_at)
                VALUES (?, ?, ?, ?, now() - make_interval(mins => ?))
                """, token, saleId, userId, status, ageMinutes);
        return token;
    }

    protected String statusOf(UUID token) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE order_token = ?", String.class, token);
    }

    protected int countOrders() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
        return n == null ? 0 : n;
    }
}
