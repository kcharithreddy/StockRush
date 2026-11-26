package com.flashsale.order;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

/**
 * Base for tests that need real PostgreSQL 16 (schema from infra/db/migration via Flyway) and Redis 7
 * with the production ACL (the service connects as its own restricted user, see {@link AclRedis}).
 * The containers start once and are shared by every test class.
 * Kafka listeners and the scheduled jobs are off: tests call the beans directly.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "flashsale.jobs.enabled=false",
        "flashsale.admin.reset-enabled=true",
        com.flashsale.order.security.TestTokens.SECRET_PROPERTY,
        // The same migrations production applies; the container's own user stands in for the app role.
        "spring.flyway.locations=filesystem:../infra/db/migration",
        "spring.flyway.placeholders.app_user=test",
        "spring.flyway.postgresql.transactional-lock=false"
})
public abstract class IntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.16")
            .withDatabaseName("flashsale");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        AclRedis.register(registry);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void cleanState() {
        jdbc.update("TRUNCATE orders");
        AclRedis.flushAll();
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
