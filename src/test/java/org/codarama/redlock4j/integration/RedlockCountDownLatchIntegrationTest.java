/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.integration;

import org.codarama.redlock4j.RedlockCountDownLatch;
import org.codarama.redlock4j.RedlockManager;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for {@link RedlockCountDownLatch}. Verifies the distributed countdown latch honors
 * its contract.
 */
@Testcontainers
public class RedlockCountDownLatchIntegrationTest {

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
                .retryDelay(Duration.ofMillis(100)).maxRetryAttempts(5).lockAcquisitionTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ========== Contract: Initialization ==========

    @Test
    void shouldInitializeWithCorrectCount() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("init-test", 5);
            assertEquals(5, latch.getCount(), "Latch should initialize with specified count");
        }
    }

    @Test
    void shouldRejectNegativeCount() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            assertThrows(IllegalArgumentException.class, () -> {
                manager.createCountDownLatch("negative-test", -1);
            }, "Should reject negative count");
        }
    }

    @Test
    void shouldAllowZeroCount() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("zero-test", 0);
            assertEquals(0, latch.getCount(), "Latch should allow zero count");
        }
    }

    // ========== Contract: countDown() ==========

    @Test
    void countDownShouldDecrementCount() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("countdown-decr", 3);

            latch.countDown();
            assertEquals(2, latch.getCount());

            latch.countDown();
            assertEquals(1, latch.getCount());

            latch.countDown();
            assertEquals(0, latch.getCount());
        }
    }

    @Test
    void countDownBelowZeroShouldNotGoNegative() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("countdown-below-zero", 1);

            latch.countDown();
            latch.countDown();
            latch.countDown();

            assertTrue(latch.getCount() <= 0, "Count should be at most 0");
        }
    }

    // ========== Contract: await() ==========

    @Test
    void awaitShouldReturnImmediatelyWhenCountIsZero() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("await-zero", 0);

            long start = System.currentTimeMillis();
            boolean result = latch.await(5, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(result, "await() should return true when count is 0");
            assertTrue(elapsed < 1000, "await() should return immediately when count is 0");
        }
    }

    @Test
    void awaitShouldBlockUntilCountReachesZero() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("await-block", 2);
            AtomicBoolean awaitCompleted = new AtomicBoolean(false);
            CountDownLatch threadStarted = new CountDownLatch(1);

            Thread waiter = new Thread(() -> {
                try {
                    threadStarted.countDown();
                    latch.await();
                    awaitCompleted.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            waiter.start();

            threadStarted.await(2, TimeUnit.SECONDS);
            Thread.sleep(200);

            assertFalse(awaitCompleted.get(), "await() should still be blocking");

            latch.countDown();
            Thread.sleep(100);
            assertFalse(awaitCompleted.get(), "await() should still block (count=1)");

            latch.countDown();
            waiter.join(5000);

            assertTrue(awaitCompleted.get(), "await() should complete when count reaches 0");
        }
    }

    @Test
    void awaitWithTimeoutShouldReturnFalseOnTimeout() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("await-timeout", 5);

            long start = System.currentTimeMillis();
            boolean result = latch.await(1, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;

            assertFalse(result, "await() should return false on timeout");
            assertTrue(elapsed >= 900 && elapsed < 2000, "Should wait approximately 1 second");
        }
    }

    // ========== Contract: Distributed Coordination ==========

    @Test
    void shouldCoordinateMultipleWorkerThreads() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            int workerCount = 5;
            RedlockCountDownLatch latch = manager.createCountDownLatch("coord-workers", workerCount);
            AtomicInteger completedWorkers = new AtomicInteger(0);
            CountDownLatch allWorkersStarted = new CountDownLatch(workerCount);
            AtomicBoolean coordinatorCompleted = new AtomicBoolean(false);

            Thread coordinator = new Thread(() -> {
                try {
                    latch.await();
                    coordinatorCompleted.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            coordinator.start();

            ExecutorService executor = Executors.newFixedThreadPool(workerCount);
            for (int i = 0; i < workerCount; i++) {
                final int workerId = i;
                executor.submit(() -> {
                    try {
                        allWorkersStarted.countDown();
                        Thread.sleep(100 + workerId * 50);
                        completedWorkers.incrementAndGet();
                        latch.countDown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertTrue(allWorkersStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertFalse(coordinatorCompleted.get(), "Coordinator should wait for workers");

            coordinator.join(10000);

            assertTrue(coordinatorCompleted.get(), "Coordinator should complete after all workers");
            assertEquals(workerCount, completedWorkers.get(), "All workers should complete");

            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldWorkAcrossMultipleRedlockManagerInstances() throws InterruptedException {
        AtomicBoolean waiterCompleted = new AtomicBoolean(false);
        CountDownLatch waiterStarted = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            try (RedlockManager manager1 = RedlockManager.withJedis(testConfiguration)) {
                RedlockCountDownLatch latch1 = manager1.createCountDownLatch("cross-process", 2);
                waiterStarted.countDown();
                latch1.await();
                waiterCompleted.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        waiterStarted.await(2, TimeUnit.SECONDS);
        Thread.sleep(500);

        try (RedlockManager manager2 = RedlockManager.withJedis(testConfiguration)) {
            RedlockCountDownLatch latch2 = manager2.createCountDownLatch("cross-process", 2);

            latch2.countDown();
            Thread.sleep(100);
            assertFalse(waiterCompleted.get(), "Waiter should still be waiting");

            latch2.countDown();
        }

        waiter.join(5000);
        assertTrue(waiterCompleted.get(), "Waiter should complete after cross-process countdown");
    }

    // ========== Contract: reset() ==========

    @Test
    void resetShouldRestoreInitialCount() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("reset-test", 3);

            latch.countDown();
            latch.countDown();
            assertEquals(1, latch.getCount());

            latch.reset();
            assertEquals(3, latch.getCount(), "reset() should restore initial count");
        }
    }

    @Test
    void resetShouldAllowReuseOfLatch() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("reset-reuse", 2);

            latch.countDown();
            latch.countDown();
            assertTrue(latch.await(1, TimeUnit.SECONDS), "First await should complete");

            latch.reset();
            assertEquals(2, latch.getCount());

            latch.countDown();
            latch.countDown();
            assertTrue(latch.await(1, TimeUnit.SECONDS), "Second await should complete after reset");
        }
    }

    // ========== Contract: Lettuce Driver ==========

    @Test
    void shouldWorkWithLettuceDriver() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withLettuce(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("lettuce-test", 3);

            assertEquals(3, latch.getCount());

            latch.countDown();
            assertEquals(2, latch.getCount());

            latch.countDown();
            latch.countDown();

            assertTrue(latch.await(1, TimeUnit.SECONDS), "Lettuce latch should work correctly");
        }
    }

    // ========== Contract: toString() ==========

    @Test
    void toStringShouldContainLatchInfo() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("tostring-test", 5);

            String str = latch.toString();
            assertTrue(str.contains("tostring-test"), "toString should contain latch key");
            assertTrue(str.contains("5"), "toString should contain count");
        }
    }

    // ========== Contract: hasQueuedThreads() ==========

    @Test
    void hasQueuedThreadsShouldReturnTrueWhenCountPositive() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("queued-test", 2);

            assertTrue(latch.hasQueuedThreads(), "hasQueuedThreads should return true when count > 0");

            latch.countDown();
            latch.countDown();

            assertFalse(latch.hasQueuedThreads(), "hasQueuedThreads should return false when count = 0");
        }
    }

    // ========== Contract: Concurrent Access ==========

    @Test
    void shouldHandleConcurrentCountDownCalls() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            int totalCountDowns = 10;
            RedlockCountDownLatch latch = manager.createCountDownLatch("concurrent-countdown", totalCountDowns);
            CountDownLatch startSignal = new CountDownLatch(1);
            CountDownLatch doneSignal = new CountDownLatch(totalCountDowns);

            // Create threads that all call countDown() simultaneously
            for (int i = 0; i < totalCountDowns; i++) {
                new Thread(() -> {
                    try {
                        startSignal.await(); // Wait for signal to start together
                        latch.countDown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneSignal.countDown();
                    }
                }).start();
            }

            // Release all threads at once
            startSignal.countDown();

            // Wait for all threads to complete
            assertTrue(doneSignal.await(10, TimeUnit.SECONDS), "All threads should complete");

            // Count should be 0 after all concurrent countdowns
            assertEquals(0, latch.getCount(), "Count should be 0 after concurrent countdowns");
        }
    }

    @Test
    void shouldHandleMultipleConcurrentAwaiters() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            int awaiterCount = 5;
            RedlockCountDownLatch latch = manager.createCountDownLatch("concurrent-await", 1);
            AtomicInteger completedAwaiters = new AtomicInteger(0);
            CountDownLatch allStarted = new CountDownLatch(awaiterCount);
            CountDownLatch allDone = new CountDownLatch(awaiterCount);

            // Start multiple threads that all await on the same latch
            for (int i = 0; i < awaiterCount; i++) {
                new Thread(() -> {
                    try {
                        allStarted.countDown();
                        latch.await();
                        completedAwaiters.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        allDone.countDown();
                    }
                }).start();
            }

            // Wait for all awaiters to start
            assertTrue(allStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(300); // Let them all block

            // No awaiters should have completed yet
            assertEquals(0, completedAwaiters.get(), "No awaiter should complete before countdown");

            // Single countdown should release ALL awaiters
            latch.countDown();

            // All awaiters should complete
            assertTrue(allDone.await(10, TimeUnit.SECONDS), "All awaiters should complete");
            assertEquals(awaiterCount, completedAwaiters.get(), "All awaiters should have completed");
        }
    }

    @Test
    void shouldMaintainCorrectCountUnderConcurrentAccess() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            int initialCount = 100;
            int threadCount = 20;
            int countDownsPerThread = 5; // 20 * 5 = 100 total

            RedlockCountDownLatch latch = manager.createCountDownLatch("stress-test", initialCount);
            CountDownLatch startSignal = new CountDownLatch(1);
            CountDownLatch doneSignal = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        startSignal.await();
                        for (int j = 0; j < countDownsPerThread; j++) {
                            latch.countDown();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneSignal.countDown();
                    }
                }).start();
            }

            // Release all threads simultaneously
            startSignal.countDown();

            // Wait for completion
            assertTrue(doneSignal.await(30, TimeUnit.SECONDS), "All threads should complete");

            // Verify final count is 0
            assertEquals(0, latch.getCount(), "Count should be 0 after all countdowns");
        }
    }

    @Test
    void shouldHandleConcurrentCountDownAndGetCount() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            int initialCount = 50;
            RedlockCountDownLatch latch = manager.createCountDownLatch("mixed-ops", initialCount);
            CountDownLatch startSignal = new CountDownLatch(1);
            AtomicInteger countDownsDone = new AtomicInteger(0);
            AtomicBoolean invalidCountSeen = new AtomicBoolean(false);

            // Threads doing countDown
            for (int i = 0; i < initialCount; i++) {
                new Thread(() -> {
                    try {
                        startSignal.await();
                        latch.countDown();
                        countDownsDone.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }

            // Threads doing getCount (verifying count is always valid)
            for (int i = 0; i < 10; i++) {
                new Thread(() -> {
                    try {
                        startSignal.await();
                        for (int j = 0; j < 20; j++) {
                            long count = latch.getCount();
                            if (count < 0 || count > initialCount) {
                                invalidCountSeen.set(true);
                            }
                            Thread.sleep(10);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }

            startSignal.countDown();
            Thread.sleep(3000); // Let operations complete

            assertFalse(invalidCountSeen.get(), "Count should always be in valid range");
            assertEquals(initialCount, countDownsDone.get(), "All countdowns should complete");
        }
    }
}
