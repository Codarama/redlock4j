/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.configuration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;

/**
 * Unit tests for RedlockConfiguration.
 */
@Tag("unit")
public class RedlockConfigurationTest {

    @Test
    public void testBasicConfiguration() {
        RedlockConfiguration config = RedlockConfiguration.builder().addRedisNode("localhost", 6379)
                .addRedisNode("localhost", 6380).addRedisNode("localhost", 6381).build();

        assertEquals(3, config.getRedisNodes().size());
        assertEquals(2, config.getQuorum()); // (3/2) + 1 = 2
        assertEquals(Duration.ofSeconds(30).toMillis(), config.getDefaultLockTimeoutMs());
        assertEquals(200, config.getRetryDelayMs());
        assertEquals(3, config.getMaxRetryAttempts());
        assertEquals(0.01, config.getClockDriftFactor(), 0.001);
    }

    @Test
    public void testCustomConfiguration() {
        RedlockConfiguration config = RedlockConfiguration.builder().addRedisNode("redis1", 6379, "password")
                .addRedisNode("redis2", 6379, "password").addRedisNode("redis3", 6379, "password")
                .defaultLockTimeout(Duration.ofSeconds(60)).retryDelay(Duration.ofMillis(500)).maxRetryAttempts(5)
                .clockDriftFactor(0.02).lockAcquisitionTimeout(Duration.ofSeconds(20)).build();

        assertEquals(3, config.getRedisNodes().size());
        assertEquals(Duration.ofSeconds(60).toMillis(), config.getDefaultLockTimeoutMs());
        assertEquals(500, config.getRetryDelayMs());
        assertEquals(5, config.getMaxRetryAttempts());
        assertEquals(0.02, config.getClockDriftFactor(), 0.001);
        assertEquals(Duration.ofSeconds(20).toMillis(), config.getLockAcquisitionTimeoutMs());
    }

    @Test
    public void testNodeConfiguration() {
        RedisNodeConfiguration nodeConfig = RedisNodeConfiguration.builder().host("redis.example.com").port(6380)
                .password("secret").database(1).connectionTimeoutMs(3000).socketTimeoutMs(3000).build();

        assertEquals("redis.example.com", nodeConfig.getHost());
        assertEquals(6380, nodeConfig.getPort());
        assertEquals("secret", nodeConfig.getPassword());
        assertEquals(1, nodeConfig.getDatabase());
        assertEquals(3000, nodeConfig.getConnectionTimeoutMs());
        assertEquals(3000, nodeConfig.getSocketTimeoutMs());
    }

    @Test
    public void testValidationErrors() {
        // Test insufficient nodes
        assertThrows(IllegalArgumentException.class, () -> {
            RedlockConfiguration.builder().addRedisNode("localhost", 6379).addRedisNode("localhost", 6380).build(); // Only
                                                                                                                    // 2
                                                                                                                    // nodes,
                                                                                                                    // need
                                                                                                                    // at
                                                                                                                    // least
                                                                                                                    // 3
        });

        // Test no nodes
        assertThrows(IllegalArgumentException.class, () -> RedlockConfiguration.builder().build());

        // Test invalid timeout
        assertThrows(IllegalArgumentException.class,
                () -> RedlockConfiguration.builder().addRedisNode("localhost", 6379).addRedisNode("localhost", 6380)
                        .addRedisNode("localhost", 6381).defaultLockTimeout(Duration.ofSeconds(-1)).build());

        // Test invalid clock drift factor
        assertThrows(IllegalArgumentException.class,
                () -> RedlockConfiguration.builder().addRedisNode("localhost", 6379).addRedisNode("localhost", 6380)
                        .addRedisNode("localhost", 6381).clockDriftFactor(1.5) // > 1.0
                        .build());
    }

    @Test
    public void testNodeConfigurationValidation() {
        // Test invalid host
        assertThrows(IllegalArgumentException.class, () -> RedisNodeConfiguration.builder().host("").build());

        // Test invalid port
        assertThrows(IllegalArgumentException.class,
                () -> RedisNodeConfiguration.builder().host("localhost").port(0).build());

        assertThrows(IllegalArgumentException.class,
                () -> RedisNodeConfiguration.builder().host("localhost").port(70000) // > 65535
                        .build());
    }

    @Test
    public void testQuorumCalculation() {
        // Test with 3 nodes
        RedlockConfiguration config3 = RedlockConfiguration.builder().addRedisNode("localhost", 6379)
                .addRedisNode("localhost", 6380).addRedisNode("localhost", 6381).build();
        assertEquals(2, config3.getQuorum());

        // Test with 5 nodes
        RedlockConfiguration config5 = RedlockConfiguration.builder().addRedisNode("localhost", 6379)
                .addRedisNode("localhost", 6380).addRedisNode("localhost", 6381).addRedisNode("localhost", 6382)
                .addRedisNode("localhost", 6383).build();
        assertEquals(3, config5.getQuorum());
    }
}
