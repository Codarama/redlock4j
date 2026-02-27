/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.driver;

import org.codarama.redlock4j.configuration.RedisNodeConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JedisRedisDriver. These tests focus on the driver's public interface and configuration handling.
 */
public class JedisRedisDriverTest {

    private RedisNodeConfiguration testConfig;
    private JedisRedisDriver driver;

    @BeforeEach
    void setUp() {
        testConfig = RedisNodeConfiguration.builder().host("localhost").port(6379).connectionTimeoutMs(5000)
                .socketTimeoutMs(5000).build();
    }

    @Test
    public void testDriverCreationWithBasicConfig() {
        driver = new JedisRedisDriver(testConfig);

        assertNotNull(driver);
        assertEquals("redis://localhost:6379", driver.getIdentifier());
    }

    @Test
    public void testDriverCreationWithPassword() {
        RedisNodeConfiguration configWithPassword = RedisNodeConfiguration.builder().host("localhost").port(6379)
                .password("testpass").connectionTimeoutMs(5000).socketTimeoutMs(5000).build();

        driver = new JedisRedisDriver(configWithPassword);

        assertNotNull(driver);
        assertEquals("redis://localhost:6379", driver.getIdentifier());
    }

    @Test
    public void testDriverCreationWithDatabase() {
        RedisNodeConfiguration configWithDb = RedisNodeConfiguration.builder().host("localhost").port(6379).database(2)
                .connectionTimeoutMs(5000).socketTimeoutMs(5000).build();

        driver = new JedisRedisDriver(configWithDb);

        assertNotNull(driver);
        assertEquals("redis://localhost:6379", driver.getIdentifier());
    }

    @Test
    public void testGetIdentifierFormat() {
        // Test identifier format without creating multiple drivers
        driver = new JedisRedisDriver(testConfig);
        assertEquals("redis://localhost:6379", driver.getIdentifier());
        driver.close();
    }

    @Test
    public void testDriverCreationWithNullConfig() {
        assertThrows(NullPointerException.class, () -> {
            new JedisRedisDriver(null);
        });
    }

    @Test
    public void testCloseDoesNotThrowException() {
        driver = new JedisRedisDriver(testConfig);

        // Should not throw exception even if connection fails
        assertDoesNotThrow(() -> driver.close());
    }

    @Test
    public void testMultipleCloseCallsAreIdempotent() {
        driver = new JedisRedisDriver(testConfig);

        // Multiple close calls should not throw exceptions
        assertDoesNotThrow(() -> {
            driver.close();
            driver.close();
            driver.close();
        });
    }
}
