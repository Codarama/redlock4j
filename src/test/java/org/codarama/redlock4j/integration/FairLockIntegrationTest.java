/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.integration;

import org.codarama.redlock4j.FairLock;
import org.codarama.redlock4j.RedlockManager;
import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link FairLock}.
 */
@Testcontainers
public class FairLockIntegrationTest {

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
    void shouldAcquireAndReleaseFairLock() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            FairLock fairLock = (FairLock) manager.createFairLock("basic-fair");
            assertTrue(fairLock.tryLock(5, TimeUnit.SECONDS));
            assertTrue(fairLock.isHeldByCurrentThread());
            fairLock.unlock();
            assertFalse(fairLock.isHeldByCurrentThread());
        }
    }

    @Test
    void shouldBlockSecondAcquirer() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            FairLock fairLock = (FairLock) manager.createFairLock("block-second");
            CountDownLatch firstAcquired = new CountDownLatch(1);
            CountDownLatch secondAttempted = new CountDownLatch(1);
            AtomicInteger secondResult = new AtomicInteger(-1);

            // First thread acquires
            assertTrue(fairLock.tryLock(5, TimeUnit.SECONDS));
            firstAcquired.countDown();

            // Second thread tries
            Thread t = new Thread(() -> {
                try {
                    firstAcquired.await();
                    boolean acquired = fairLock.tryLock(500, TimeUnit.MILLISECONDS);
                    secondResult.set(acquired ? 1 : 0);
                    secondAttempted.countDown();
                    if (acquired)
                        fairLock.unlock();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();

            assertTrue(secondAttempted.await(5, TimeUnit.SECONDS));
            assertEquals(0, secondResult.get(), "Second thread should be blocked");
            fairLock.unlock();
        }
    }

    // ========== Reentrancy ==========

    @Test
    void shouldSupportReentrantLocking() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            FairLock fairLock = (FairLock) manager.createFairLock("reentrant-fair");

            assertTrue(fairLock.tryLock(5, TimeUnit.SECONDS));
            assertEquals(1, fairLock.getHoldCount());

            assertTrue(fairLock.tryLock(5, TimeUnit.SECONDS));
            assertEquals(2, fairLock.getHoldCount());

            fairLock.unlock();
            assertEquals(1, fairLock.getHoldCount());
            assertTrue(fairLock.isHeldByCurrentThread());

            fairLock.unlock();
            assertEquals(0, fairLock.getHoldCount());
            assertFalse(fairLock.isHeldByCurrentThread());
        }
    }

    // ========== FIFO Ordering ==========

    @Test
    void shouldAcquireInFIFOOrder() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            FairLock fairLock = (FairLock) manager.createFairLock("fifo-order");
            List<Integer> acquisitionOrder = Collections.synchronizedList(new ArrayList<>());
            int threadCount = 3;
            CountDownLatch firstHeld = new CountDownLatch(1);
            CountDownLatch allQueued = new CountDownLatch(threadCount - 1);
            CountDownLatch allDone = new CountDownLatch(threadCount);

            // First thread acquires and holds
            new Thread(() -> {
                try {
                    if (fairLock.tryLock(10, TimeUnit.SECONDS)) {
                        acquisitionOrder.add(0);
                        firstHeld.countDown();
                        allQueued.await(); // Wait for others to queue
                        Thread.sleep(200); // Hold briefly
                        fairLock.unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            }).start();

            assertTrue(firstHeld.await(10, TimeUnit.SECONDS));

            // Other threads queue up in order
            for (int i = 1; i < threadCount; i++) {
                final int idx = i;
                Thread.sleep(100); // Stagger to ensure ordering
                new Thread(() -> {
                    try {
                        allQueued.countDown();
                        if (fairLock.tryLock(30, TimeUnit.SECONDS)) {
                            acquisitionOrder.add(idx);
                            fairLock.unlock();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        allDone.countDown();
                    }
                }).start();
            }

            assertTrue(allDone.await(60, TimeUnit.SECONDS));
            assertEquals(threadCount, acquisitionOrder.size(), "All threads should acquire");
        }
    }

    // ========== Timeout ==========

    @Test
    void shouldTimeoutWhenLockNotAvailable() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            FairLock fairLock = (FairLock) manager.createFairLock("timeout-test");

            assertTrue(fairLock.tryLock(5, TimeUnit.SECONDS));

            long start = System.currentTimeMillis();
            Thread t = new Thread(() -> {
                try {
                    assertFalse(fairLock.tryLock(500, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            t.join();
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed >= 400 && elapsed < 2000, "Should timeout after ~500ms");
            fairLock.unlock();
        }
    }

    // ========== Concurrent Access ==========

    @Test
    void shouldHandleConcurrentAcquisitions() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            FairLock fairLock = (FairLock) manager.createFairLock("concurrent-fair");
            int threadCount = 5;
            AtomicInteger successCount = new AtomicInteger(0);
            CountDownLatch startSignal = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        startSignal.await();
                        if (fairLock.tryLock(30, TimeUnit.SECONDS)) {
                            try {
                                successCount.incrementAndGet();
                                Thread.sleep(50);
                            } finally {
                                fairLock.unlock();
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

    @Test
    void shouldHandleRapidLockUnlockCycles() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            FairLock fairLock = (FairLock) manager.createFairLock("rapid-cycles");
            int cycles = 5;
            int threadCount = 3;
            AtomicInteger totalAcquisitions = new AtomicInteger(0);
            CountDownLatch done = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        for (int j = 0; j < cycles; j++) {
                            if (fairLock.tryLock(10, TimeUnit.SECONDS)) {
                                totalAcquisitions.incrementAndGet();
                                Thread.sleep(20);
                                fairLock.unlock();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }).start();
            }

            assertTrue(done.await(120, TimeUnit.SECONDS));
            assertTrue(totalAcquisitions.get() > 0, "Should have successful acquisitions");
        }
    }

    // ========== Utility Methods ==========

    @Test
    void shouldReportValidityTime() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            FairLock fairLock = (FairLock) manager.createFairLock("validity-time");

            assertEquals(0, fairLock.getRemainingValidityTime());

            assertTrue(fairLock.tryLock(5, TimeUnit.SECONDS));
            assertTrue(fairLock.getRemainingValidityTime() > 0);

            fairLock.unlock();
        }
    }

    // ========== Lettuce Driver ==========

    @Test
    void shouldWorkWithLettuceDriver() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withLettuce(testConfiguration)) {
            FairLock fairLock = (FairLock) manager.createFairLock("lettuce-fair");
            assertTrue(fairLock.tryLock(5, TimeUnit.SECONDS));
            assertTrue(fairLock.isHeldByCurrentThread());
            fairLock.unlock();
        }
    }
}
