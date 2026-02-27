/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.integration;

import org.codarama.redlock4j.RedlockManager;
import org.codarama.redlock4j.RedlockSemaphore;
import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link RedlockSemaphore}.
 */
@Testcontainers
public class RedlockSemaphoreIntegrationTest {

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
                .retryDelay(Duration.ofMillis(50)).maxRetryAttempts(10).lockAcquisitionTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ========== Initialization ==========

    @Test
    void shouldCreateSemaphoreWithPositivePermits() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockSemaphore semaphore = manager.createSemaphore("init-test", 5);
            assertNotNull(semaphore);
        }
    }

    @Test
    void shouldRejectZeroPermits() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            assertThrows(IllegalArgumentException.class, () -> manager.createSemaphore("zero-permits", 0));
        }
    }

    @Test
    void shouldRejectNegativePermits() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            assertThrows(IllegalArgumentException.class, () -> manager.createSemaphore("negative-permits", -1));
        }
    }

    // ========== acquire() / release() ==========

    @Test
    void shouldAcquireAndReleaseSinglePermit() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockSemaphore semaphore = manager.createSemaphore("single-permit", 3);
            semaphore.acquire();
            semaphore.release();
        }
    }

    @Test
    void shouldAcquireMultiplePermits() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockSemaphore semaphore = manager.createSemaphore("multi-permit", 5);
            semaphore.acquire(3);
            semaphore.release(3);
        }
    }

    // ========== tryAcquire() ==========

    @Test
    void tryAcquireShouldReturnTrueWhenPermitAvailable() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockSemaphore semaphore = manager.createSemaphore("try-acquire", 2);
            assertTrue(semaphore.tryAcquire(1, TimeUnit.SECONDS));
            semaphore.release();
        }
    }

    @Test
    void sameThreadCannotAcquireMultipleTimes() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockSemaphore semaphore = manager.createSemaphore("same-thread", 5);
            assertTrue(semaphore.tryAcquire(1, TimeUnit.SECONDS));
            // Same thread should fail to acquire again while holding
            assertFalse(semaphore.tryAcquire(500, TimeUnit.MILLISECONDS));
            semaphore.release();
            // After release, can acquire again
            assertTrue(semaphore.tryAcquire(1, TimeUnit.SECONDS));
            semaphore.release();
        }
    }

    @Test
    void tryAcquireWithZeroTimeoutShouldReturnImmediately() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockSemaphore semaphore = manager.createSemaphore("zero-timeout", 1);
            assertTrue(semaphore.tryAcquire());
            semaphore.release();
        }
    }

    // ========== Permit Validation ==========

    @Test
    void shouldRejectAcquiringMorePermitsThanMax() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockSemaphore semaphore = manager.createSemaphore("exceed-max", 3);
            assertThrows(IllegalArgumentException.class, () -> semaphore.tryAcquire(5, 1, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldRejectAcquiringZeroPermits() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockSemaphore semaphore = manager.createSemaphore("zero-acquire", 3);
            assertThrows(IllegalArgumentException.class, () -> semaphore.tryAcquire(0, 1, TimeUnit.SECONDS));
        }
    }

    // ========== Concurrent Access ==========

    @Test
    void shouldAllowMultipleThreadsToAcquirePermits() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            int threadCount = 5;
            RedlockSemaphore semaphore = manager.createSemaphore("multi-thread-acq", 10);

            AtomicInteger successCount = new AtomicInteger(0);
            CountDownLatch startSignal = new CountDownLatch(1);
            CountDownLatch doneSignal = new CountDownLatch(threadCount);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startSignal.await();
                        if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                            try {
                                successCount.incrementAndGet();
                                Thread.sleep(100);
                            } finally {
                                semaphore.release();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneSignal.countDown();
                    }
                });
            }

            startSignal.countDown();
            assertTrue(doneSignal.await(30, TimeUnit.SECONDS), "All threads should complete");
            assertEquals(threadCount, successCount.get(), "All threads should acquire");
            executor.shutdown();
        }
    }

    @Test
    void shouldHandleRapidAcquireReleaseCycles() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            int cycles = 10;
            int threadCount = 3;
            RedlockSemaphore semaphore = manager.createSemaphore("rapid-cycles", 5);

            AtomicInteger totalAcquisitions = new AtomicInteger(0);
            CountDownLatch done = new CountDownLatch(threadCount);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < cycles; j++) {
                            if (semaphore.tryAcquire(2, TimeUnit.SECONDS)) {
                                totalAcquisitions.incrementAndGet();
                                Thread.sleep(20);
                                semaphore.release();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(done.await(60, TimeUnit.SECONDS), "All threads should complete");
            assertTrue(totalAcquisitions.get() > 0, "Should have successful acquisitions");
            executor.shutdown();
        }
    }

    @Test
    void shouldAllowConcurrentAcquisitionsFromDifferentThreads() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            int threadCount = 4;
            RedlockSemaphore semaphore = manager.createSemaphore("concurrent-diff-threads", 10);

            AtomicInteger acquired = new AtomicInteger(0);
            CountDownLatch allAcquired = new CountDownLatch(threadCount);
            CountDownLatch releaseSignal = new CountDownLatch(1);
            CountDownLatch allDone = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                            acquired.incrementAndGet();
                            allAcquired.countDown();
                            releaseSignal.await();
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        allDone.countDown();
                    }
                }).start();
            }

            assertTrue(allAcquired.await(10, TimeUnit.SECONDS), "All should acquire");
            assertEquals(threadCount, acquired.get(), "All threads should hold permits");

            releaseSignal.countDown();
            assertTrue(allDone.await(5, TimeUnit.SECONDS));
        }
    }

    // ========== Lettuce Driver ==========

    @Test
    void shouldWorkWithLettuceDriver() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withLettuce(testConfiguration)) {
            RedlockSemaphore semaphore = manager.createSemaphore("lettuce-sem", 3);
            assertTrue(semaphore.tryAcquire(2, TimeUnit.SECONDS));
            semaphore.release();
        }
    }

    @Test
    void shouldHandleConcurrencyWithLettuce() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withLettuce(testConfiguration)) {
            int threadCount = 4;
            RedlockSemaphore semaphore = manager.createSemaphore("lettuce-concurrent", 10);

            AtomicInteger successCount = new AtomicInteger(0);
            CountDownLatch done = new CountDownLatch(threadCount);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                            try {
                                successCount.incrementAndGet();
                                Thread.sleep(100);
                            } finally {
                                semaphore.release();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertEquals(threadCount, successCount.get(), "All threads should acquire with Lettuce");
            executor.shutdown();
        }
    }
}
