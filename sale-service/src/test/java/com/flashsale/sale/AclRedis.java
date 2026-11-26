package com.flashsale.sale;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Redis 7 started exactly like production: infra/redis/start.sh renders infra/redis/users.acl.template.
 * The application under test connects as its own restricted user ({@value #SERVICE_USER}); tests use
 * {@link #admin()} (the operator account) only to arrange and clean up data.
 */
public final class AclRedis {

    public static final String SERVICE_USER = "sale-service";
    private static final String ADMIN_PASSWORD = "test-admin-password";
    private static final String SERVICE_PASSWORD = "test-service-password";

    public static final GenericContainer<?> CONTAINER = new GenericContainer<>("redis:7.4.12-alpine")
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/redis/users.acl.template"), "/etc/redis/users.acl.template")
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/redis/start.sh"), "/etc/redis/start.sh")
            .withEnv("REDIS_PASSWORD", ADMIN_PASSWORD)
            .withEnv("REDIS_SALE_PASSWORD", SERVICE_PASSWORD)
            .withEnv("REDIS_ORDER_PASSWORD", SERVICE_PASSWORD)
            .withCommand("sh", "/etc/redis/start.sh")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    private static StringRedisTemplate admin;

    static {
        CONTAINER.start();
    }

    private AclRedis() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", CONTAINER::getHost);
        registry.add("spring.data.redis.port", () -> CONTAINER.getMappedPort(6379));
        registry.add("spring.data.redis.username", () -> SERVICE_USER);
        registry.add("spring.data.redis.password", () -> SERVICE_PASSWORD);
    }

    /** Operator connection, for arranging test data and FLUSHALL — things the service itself may not do. */
    public static synchronized StringRedisTemplate admin() {
        if (admin == null) {
            RedisStandaloneConfiguration config =
                    new RedisStandaloneConfiguration(CONTAINER.getHost(), CONTAINER.getMappedPort(6379));
            config.setPassword(ADMIN_PASSWORD);
            LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            admin = new StringRedisTemplate(factory);
        }
        return admin;
    }

    public static void flushAll() {
        admin().getConnectionFactory().getConnection().serverCommands().flushAll();
    }
}
