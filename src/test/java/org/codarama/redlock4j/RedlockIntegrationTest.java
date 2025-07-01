/*
 * MIT License
 *
 * Copyright (c) 2025 Codarama
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.codarama.redlock4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.List;
import java.util.ArrayList;

/**
 * Integration tests for Redlock functionality using Testcontainers.
 * These tests automatically spin up Redis containers for testing.
 */
@Testcontainers
public class RedlockIntegrationTest {

    // Create 3 Redis containers for Redlock testing
    @Container
    static GenericContainer<?> redis1 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");

    @Container
    static GenericContainer<?> redis2 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");

    @Container
    static GenericContainer<?> redis3 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");

    private static RedlockConfiguration testConfiguration;

    @BeforeAll
    static void setUp() {
        // Wait for all containers to be ready
        redis1.start();
        redis2.start();
        redis3.start();

        // Create configuration with dynamic ports from containers
        testConfiguration = RedlockConfiguration.builder()
            .addRedisNode("localhost", redis1.getMappedPort(6379))
            .addRedisNode("localhost", redis2.getMappedPort(6379))
            .addRedisNode("localhost", redis3.getMappedPort(6379))
            .defaultLockTimeout(10, TimeUnit.SECONDS)
            .retryDelay(100, TimeUnit.MILLISECONDS)
            .maxRetryAttempts(3)
            .lockAcquisitionTimeout(5, TimeUnit.SECONDS)
            .build();
    }

    @AfterAll
    static void tearDown() {
        // Containers are automatically stopped by Testcontainers
    }
    
    @Test
    public void testJedisBasicLockOperations() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            assertTrue(manager.isHealthy(), "Manager should be healthy with connected Redis nodes");
            assertEquals(3, manager.getConnectedNodeCount(), "Should have 3 connected nodes");
            assertEquals(2, manager.getQuorum(), "Quorum should be 2 for 3 nodes");

            Lock lock = manager.createLock("test-lock-jedis");

            // Test basic lock/unlock
            assertTrue(lock.tryLock(), "Should be able to acquire lock");

            if (lock instanceof RedlockLock) {
                RedlockLock redlockLock = (RedlockLock) lock;
                assertTrue(redlockLock.isHeldByCurrentThread(), "Lock should be held by current thread");
                assertTrue(redlockLock.getRemainingValidityTime() > 0, "Lock should have remaining validity time");
            }

            lock.unlock();

            if (lock instanceof RedlockLock) {
                RedlockLock redlockLock = (RedlockLock) lock;
                assertFalse(redlockLock.isHeldByCurrentThread(), "Lock should not be held after unlock");
            }
        }
    }
    
    @Test
    public void testLettuceBasicLockOperations() {
        try (RedlockManager manager = RedlockManager.withLettuce(testConfiguration)) {
            assertTrue(manager.isHealthy(), "Manager should be healthy with connected Redis nodes");
            assertEquals(3, manager.getConnectedNodeCount(), "Should have 3 connected nodes");

            Lock lock = manager.createLock("test-lock-lettuce");

            // Test basic lock/unlock
            assertTrue(lock.tryLock(), "Should be able to acquire lock");
            lock.unlock();
        }
    }
    
    @Test
    public void testLockTimeout() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            Lock lock = manager.createLock("test-timeout-lock");

            // Test tryLock with timeout
            assertTrue(lock.tryLock(1, TimeUnit.SECONDS), "Should acquire lock within timeout");
            lock.unlock();

            // Test immediate tryLock
            assertTrue(lock.tryLock(), "Should acquire lock immediately");
            lock.unlock();
        }
    }
    
    @Test
    public void testConcurrentLockAccess() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            Lock lock1 = manager.createLock("concurrent-test-lock");
            Lock lock2 = manager.createLock("concurrent-test-lock"); // Same key

            // First lock should succeed
            assertTrue(lock1.tryLock(), "First lock should succeed");

            // Second lock should fail (same resource)
            assertFalse(lock2.tryLock(), "Second lock should fail for same resource");

            // Release first lock
            lock1.unlock();

            // Now second lock should succeed
            assertTrue(lock2.tryLock(), "Second lock should succeed after first is released");
            lock2.unlock();
        }
    }
    
    @Test
    public void testLockWithCustomConfiguration() {
        RedlockConfiguration config = RedlockConfiguration.builder()
            .addRedisNode(RedisNodeConfiguration.builder()
                .host("localhost")
                .port(redis1.getMappedPort(6379))
                .connectionTimeoutMs(1000)
                .socketTimeoutMs(1000)
                .build())
            .addRedisNode(RedisNodeConfiguration.builder()
                .host("localhost")
                .port(redis2.getMappedPort(6379))
                .connectionTimeoutMs(1000)
                .socketTimeoutMs(1000)
                .build())
            .addRedisNode(RedisNodeConfiguration.builder()
                .host("localhost")
                .port(redis3.getMappedPort(6379))
                .connectionTimeoutMs(1000)
                .socketTimeoutMs(1000)
                .build())
            .defaultLockTimeout(5, TimeUnit.SECONDS)
            .retryDelay(50, TimeUnit.MILLISECONDS)
            .maxRetryAttempts(2)
            .clockDriftFactor(0.02)
            .build();

        try (RedlockManager manager = RedlockManager.withJedis(config)) {
            Lock lock = manager.createLock("custom-config-lock");

            assertTrue(lock.tryLock(), "Should acquire lock with custom configuration");
            lock.unlock();
        }
    }
    
    @Test
    public void testManagerLifecycle() {
        RedlockManager manager = RedlockManager.withJedis(testConfiguration);
        assertTrue(manager.isHealthy(), "Manager should be healthy when created");

        Lock lock = manager.createLock("lifecycle-test-lock");
        assertTrue(lock.tryLock(), "Should be able to create and use locks");
        lock.unlock();

        manager.close();
        assertEquals(0, manager.getConnectedNodeCount(), "Should have no connected nodes after close");
        assertFalse(manager.isHealthy(), "Manager should not be healthy after close");

        // Should throw exception when trying to create locks after close
        assertThrows(RedlockException.class, () -> {
            manager.createLock("should-fail");
        });
    }
    
    @Test
    public void testInvalidLockKey() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            // Test null key
            assertThrows(IllegalArgumentException.class, () -> {
                manager.createLock(null);
            });

            // Test empty key
            assertThrows(IllegalArgumentException.class, () -> {
                manager.createLock("");
            });

            // Test whitespace-only key
            assertThrows(IllegalArgumentException.class, () -> {
                manager.createLock("   ");
            });
        }
    }

    @Test
    public void testRedisContainerConnectivity() {
        // Test that all Redis containers are accessible
        assertTrue(redis1.isRunning(), "Redis container 1 should be running");
        assertTrue(redis2.isRunning(), "Redis container 2 should be running");
        assertTrue(redis3.isRunning(), "Redis container 3 should be running");

        // Test that ports are mapped correctly
        assertTrue(redis1.getMappedPort(6379) > 0, "Redis container 1 should have mapped port");
        assertTrue(redis2.getMappedPort(6379) > 0, "Redis container 2 should have mapped port");
        assertTrue(redis3.getMappedPort(6379) > 0, "Redis container 3 should have mapped port");

        System.out.println("Redis containers running on ports: " +
                redis1.getMappedPort(6379) + ", " +
                redis2.getMappedPort(6379) + ", " +
                redis3.getMappedPort(6379));
    }
}
