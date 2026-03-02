/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.integration;

import org.codarama.redlock4j.MultiLock;
import org.codarama.redlock4j.RedlockManager;
import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MultiLock}.
 */
@Tag("integration")
@Testcontainers
public class MultiLockIntegrationTest {

    @Container
    static GenericContainer<?> redis1 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379).withCommand("redis-server", "--appendonly", "yes");

    @Container
    static GenericContainer<?> redis2 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379).withCommand("redis-server", "--appendonly", "yes");

    @Container
    static GenericContainer<?> redis3 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379).withCommand("redis-server", "--appendonly", "yes");

    private static RedlockConfiguration testConfiguration;

    @BeforeAll
    static void setUp() {
        testConfiguration = RedlockConfiguration.builder().addRedisNode("localhost", redis1.getMappedPort(6379))
                .addRedisNode("localhost", redis2.getMappedPort(6379))
                .addRedisNode("localhost", redis3.getMappedPort(6379)).defaultLockTimeout(Duration.ofSeconds(30))
                .lockAcquisitionTimeout(Duration.ofSeconds(10)).retryDelay(Duration.ofMillis(50)).maxRetryAttempts(100)
                .build();
    }

    // ========== Basic Functionality ==========

    @Test
    void shouldAcquireAndReleaseMultipleLocks() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            List<String> keys = Arrays.asList("account:1", "account:2", "account:3");
            Lock multiLock = manager.createMultiLock(keys);

            assertTrue(multiLock.tryLock(5, TimeUnit.SECONDS));
            multiLock.unlock();
        }
    }

    @Test
    void shouldRejectEmptyKeyList() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            assertThrows(IllegalArgumentException.class, () -> manager.createMultiLock(Arrays.asList()));
        }
    }

    @Test
    void shouldRejectNullKeyList() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            assertThrows(IllegalArgumentException.class, () -> manager.createMultiLock(null));
        }
    }

    // ========== All-or-Nothing Semantics ==========

    @Test
    void shouldBlockIfAnyResourceIsLocked() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            // First, acquire a single lock on one resource
            Lock singleLock = manager.createLock("resource:B");
            assertTrue(singleLock.tryLock(5, TimeUnit.SECONDS));

            // Try to acquire multi-lock that includes resource:B
            List<String> keys = Arrays.asList("resource:A", "resource:B", "resource:C");
            Lock multiLock = manager.createMultiLock(keys);

            // Should fail with short timeout since resource:B is already held
            assertFalse(multiLock.tryLock(500, TimeUnit.MILLISECONDS));

            singleLock.unlock();
        }
    }

    @Test
    void shouldAcquireAfterConflictingLockReleased() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            Lock singleLock = manager.createLock("resource:X");
            assertTrue(singleLock.tryLock(5, TimeUnit.SECONDS));

            List<String> keys = Arrays.asList("resource:W", "resource:X", "resource:Y");
            Lock multiLock = manager.createMultiLock(keys);
            CountDownLatch released = new CountDownLatch(1);
            AtomicBoolean multiAcquired = new AtomicBoolean(false);

            Thread t = new Thread(() -> {
                try {
                    released.await();
                    if (multiLock.tryLock(10, TimeUnit.SECONDS)) {
                        multiAcquired.set(true);
                        multiLock.unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();

            Thread.sleep(100);
            singleLock.unlock();
            released.countDown();

            t.join(15000);
            assertTrue(multiAcquired.get(), "Multi-lock should acquire after single lock released");
        }
    }

    // ========== Exclusive Access ==========

    @Test
    void twoMultiLocksWithOverlapShouldNotCoexist() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            List<String> keys1 = Arrays.asList("shared:1", "shared:2");
            List<String> keys2 = Arrays.asList("shared:2", "shared:3");

            Lock multiLock1 = manager.createMultiLock(keys1);
            Lock multiLock2 = manager.createMultiLock(keys2);

            assertTrue(multiLock1.tryLock(5, TimeUnit.SECONDS));
            // multiLock2 overlaps on shared:2, should fail
            assertFalse(multiLock2.tryLock(500, TimeUnit.MILLISECONDS));

            multiLock1.unlock();
            // Now multiLock2 should succeed
            assertTrue(multiLock2.tryLock(5, TimeUnit.SECONDS));
            multiLock2.unlock();
        }
    }

    // ========== Concurrent Access ==========

    @Test
    void shouldHandleConcurrentMultiLockAcquisitions() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            int threadCount = 5;
            AtomicInteger successCount = new AtomicInteger(0);
            CountDownLatch startSignal = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                new Thread(() -> {
                    try {
                        // Each thread locks different resources
                        List<String> keys = Arrays.asList("t" + idx + ":a", "t" + idx + ":b");
                        Lock multiLock = manager.createMultiLock(keys);
                        startSignal.await();
                        if (multiLock.tryLock(10, TimeUnit.SECONDS)) {
                            try {
                                successCount.incrementAndGet();
                                Thread.sleep(50);
                            } finally {
                                multiLock.unlock();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }).start();
            }

            startSignal.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertEquals(threadCount, successCount.get(), "All threads should acquire non-overlapping locks");
        }
    }

    @Test
    void shouldHandleConcurrentAccessToSameResources() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            int threadCount = 4;
            List<String> keys = Arrays.asList("contested:A", "contested:B");
            AtomicInteger successCount = new AtomicInteger(0);
            CountDownLatch startSignal = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        Lock multiLock = manager.createMultiLock(keys);
                        startSignal.await();
                        if (multiLock.tryLock(30, TimeUnit.SECONDS)) {
                            try {
                                successCount.incrementAndGet();
                                Thread.sleep(50);
                            } finally {
                                multiLock.unlock();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }).start();
            }

            startSignal.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS));
            assertEquals(threadCount, successCount.get(), "All threads should eventually acquire");
        }
    }

    // ========== Key Ordering (Deadlock Prevention) ==========

    @Test
    void shouldPreventDeadlockWithDifferentKeyOrders() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            // Thread 1 requests A,B,C; Thread 2 requests C,B,A
            // Internal sorting should make both request A,B,C
            List<String> keys1 = Arrays.asList("deadlock:A", "deadlock:B", "deadlock:C");
            List<String> keys2 = Arrays.asList("deadlock:C", "deadlock:B", "deadlock:A");

            AtomicInteger success1 = new AtomicInteger(0);
            AtomicInteger success2 = new AtomicInteger(0);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);

            new Thread(() -> {
                try {
                    Lock ml = manager.createMultiLock(keys1);
                    start.await();
                    if (ml.tryLock(30, TimeUnit.SECONDS)) {
                        success1.incrementAndGet();
                        Thread.sleep(100);
                        ml.unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();

            new Thread(() -> {
                try {
                    Lock ml = manager.createMultiLock(keys2);
                    start.await();
                    if (ml.tryLock(30, TimeUnit.SECONDS)) {
                        success2.incrementAndGet();
                        Thread.sleep(100);
                        ml.unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();

            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS));
            assertEquals(1, success1.get());
            assertEquals(1, success2.get());
        }
    }

    // ========== Lettuce Driver ==========

    @Test
    void shouldWorkWithLettuceDriver() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withLettuce(testConfiguration)) {
            List<String> keys = Arrays.asList("lettuce:1", "lettuce:2");
            Lock multiLock = manager.createMultiLock(keys);

            assertTrue(multiLock.tryLock(5, TimeUnit.SECONDS));
            multiLock.unlock();
        }
    }
}
