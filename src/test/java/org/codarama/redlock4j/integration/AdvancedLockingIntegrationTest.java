/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.integration;

import org.codarama.redlock4j.*;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for advanced locking primitives.
 */
@Tag("integration")
@Testcontainers
public class AdvancedLockingIntegrationTest {

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
                .addRedisNode("localhost", redis3.getMappedPort(6379)).defaultLockTimeout(Duration.ofSeconds(10))
                .retryDelay(Duration.ofMillis(100)).maxRetryAttempts(5).lockAcquisitionTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    public void testFairLockBasic() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            Lock fairLock = manager.createFairLock("test-fair-lock");

            assertTrue(fairLock.tryLock(), "Should acquire fair lock");
            fairLock.unlock();
        }
    }

    @Test
    public void testFairLockWithLettuce() {
        try (RedlockManager manager = RedlockManager.withLettuce(testConfiguration)) {
            Lock fairLock = manager.createFairLock("test-fair-lock-lettuce");

            assertTrue(fairLock.tryLock(), "Should acquire fair lock with Lettuce");
            fairLock.unlock();
        }
    }

    @Test
    public void testMultiLockBasic() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            Lock multiLock = manager.createMultiLock(Arrays.asList("resource1", "resource2", "resource3"));

            assertTrue(multiLock.tryLock(), "Should acquire all locks atomically");
            multiLock.unlock();
        }
    }

    @Test
    public void testMultiLockWithLettuce() {
        try (RedlockManager manager = RedlockManager.withLettuce(testConfiguration)) {
            Lock multiLock = manager.createMultiLock(Arrays.asList("res-a", "res-b"));

            assertTrue(multiLock.tryLock(), "Should acquire MultiLock with Lettuce");
            multiLock.unlock();
        }
    }

    @Test
    public void testSemaphoreBasic() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockSemaphore semaphore = manager.createSemaphore("test-semaphore", 3);

            semaphore.acquire();
            semaphore.release();
        }
    }

    @Test
    public void testSemaphoreWithLettuce() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withLettuce(testConfiguration)) {
            RedlockSemaphore semaphore = manager.createSemaphore("test-semaphore-lettuce", 2);

            semaphore.acquire();
            semaphore.release();
        }
    }

    @Test
    public void testSemaphoreConcurrentAccess() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            int permits = 2;
            int threadCount = 5;
            RedlockSemaphore semaphore = manager.createSemaphore("test-semaphore-concurrent", permits);

            AtomicInteger successCount = new AtomicInteger(0);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        semaphore.acquire();
                        successCount.incrementAndGet();
                        Thread.sleep(50);
                        semaphore.release();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            assertTrue(endLatch.await(30, TimeUnit.SECONDS), "All threads should complete");
            assertEquals(threadCount, successCount.get(), "All threads should acquire semaphore");
        }
    }

    @Test
    public void testReadWriteLockBasic() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockReadWriteLock rwLock = manager.createReadWriteLock("test-rwlock");

            Lock readLock = rwLock.readLock();
            assertTrue(readLock.tryLock(), "Should acquire read lock");
            readLock.unlock();

            Lock writeLock = rwLock.writeLock();
            assertTrue(writeLock.tryLock(), "Should acquire write lock");
            writeLock.unlock();
        }
    }

    @Test
    public void testReadWriteLockWithLettuce() {
        try (RedlockManager manager = RedlockManager.withLettuce(testConfiguration)) {
            RedlockReadWriteLock rwLock = manager.createReadWriteLock("test-rwlock-lettuce");

            Lock readLock = rwLock.readLock();
            assertTrue(readLock.tryLock(), "Should acquire read lock with Lettuce");
            readLock.unlock();

            Lock writeLock = rwLock.writeLock();
            assertTrue(writeLock.tryLock(), "Should acquire write lock with Lettuce");
            writeLock.unlock();
        }
    }

    @Test
    public void testCountDownLatchBasic() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("test-latch", 3);

            assertEquals(3, latch.getCount(), "Initial count should be 3");

            latch.countDown();
            assertEquals(2, latch.getCount(), "Count should be 2 after first countdown");

            latch.countDown();
            assertEquals(1, latch.getCount(), "Count should be 1 after second countdown");

            latch.countDown();
            assertEquals(0, latch.getCount(), "Count should be 0 after third countdown");
        }
    }

    @Test
    public void testCountDownLatchWithLettuce() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withLettuce(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("test-latch-lettuce", 2);

            assertEquals(2, latch.getCount(), "Initial count should be 2 with Lettuce");
            latch.countDown();
            assertEquals(1, latch.getCount(), "Count should be 1 after countdown with Lettuce");
        }
    }

    @Test
    public void testCountDownLatchAwait() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockCountDownLatch latch = manager.createCountDownLatch("test-latch-await", 2);
            AtomicInteger awaitCompleted = new AtomicInteger(0);
            CountDownLatch threadStarted = new CountDownLatch(1);

            // Start thread that waits
            new Thread(() -> {
                try (RedlockManager mgr = RedlockManager.withJedis(testConfiguration)) {
                    RedlockCountDownLatch waitLatch = mgr.createCountDownLatch("test-latch-await", 2);
                    threadStarted.countDown();
                    if (waitLatch.await(10, TimeUnit.SECONDS)) {
                        awaitCompleted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();

            // Wait for thread to start
            assertTrue(threadStarted.await(2, TimeUnit.SECONDS), "Thread should start");
            Thread.sleep(500); // Give time for subscription

            // Count down
            latch.countDown();
            latch.countDown();

            // Wait for await to complete
            Thread.sleep(1000);
            assertEquals(1, awaitCompleted.get(), "Await should complete after countdown reaches zero");
        }
    }

    @Test
    public void testMultipleConcurrentReaders() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockReadWriteLock rwLock = manager.createReadWriteLock("test-rwlock-concurrent");

            int readerCount = 3;
            AtomicInteger concurrentReaders = new AtomicInteger(0);
            AtomicInteger maxConcurrent = new AtomicInteger(0);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(readerCount);

            for (int i = 0; i < readerCount; i++) {
                new Thread(() -> {
                    try (RedlockManager mgr = RedlockManager.withJedis(testConfiguration)) {
                        RedlockReadWriteLock lock = mgr.createReadWriteLock("test-rwlock-concurrent");
                        Lock readLock = lock.readLock();

                        startLatch.await();
                        readLock.lock();

                        int current = concurrentReaders.incrementAndGet();
                        maxConcurrent.updateAndGet(max -> Math.max(max, current));

                        Thread.sleep(100);

                        concurrentReaders.decrementAndGet();
                        readLock.unlock();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            assertTrue(endLatch.await(15, TimeUnit.SECONDS), "All readers should complete");
            assertTrue(maxConcurrent.get() > 1, "Multiple readers should access concurrently");
        }
    }
}
