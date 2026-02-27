/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.integration;

import org.codarama.redlock4j.RedlockManager;
import org.codarama.redlock4j.RedlockReadWriteLock;
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
import java.util.concurrent.locks.Lock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link RedlockReadWriteLock}.
 */
@Testcontainers
public class RedlockReadWriteLockIntegrationTest {

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
    void shouldAcquireAndReleaseReadLock() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockReadWriteLock rwLock = manager.createReadWriteLock("basic-read");
            Lock readLock = rwLock.readLock();

            assertTrue(readLock.tryLock(5, TimeUnit.SECONDS));
            readLock.unlock();
        }
    }

    @Test
    void shouldAcquireAndReleaseWriteLock() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockReadWriteLock rwLock = manager.createReadWriteLock("basic-write");
            Lock writeLock = rwLock.writeLock();

            assertTrue(writeLock.tryLock(5, TimeUnit.SECONDS));
            writeLock.unlock();
        }
    }

    // ========== Multiple Readers ==========

    @Test
    void shouldAllowMultipleConcurrentReaders() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockReadWriteLock rwLock = manager.createReadWriteLock("multi-readers");
            int readerCount = 5;
            AtomicInteger acquired = new AtomicInteger(0);
            CountDownLatch allAcquired = new CountDownLatch(readerCount);
            CountDownLatch releaseSignal = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(readerCount);

            for (int i = 0; i < readerCount; i++) {
                new Thread(() -> {
                    try {
                        Lock readLock = rwLock.readLock();
                        if (readLock.tryLock(10, TimeUnit.SECONDS)) {
                            try {
                                acquired.incrementAndGet();
                                allAcquired.countDown();
                                releaseSignal.await();
                            } finally {
                                readLock.unlock();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }).start();
            }

            assertTrue(allAcquired.await(15, TimeUnit.SECONDS), "All readers should acquire");
            assertEquals(readerCount, acquired.get(), "All readers should hold the lock simultaneously");

            releaseSignal.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        }
    }

    // ========== Writer Exclusivity ==========

    @Test
    void writerShouldHaveExclusiveAccess() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockReadWriteLock rwLock = manager.createReadWriteLock("writer-exclusive");
            Lock writeLock = rwLock.writeLock();
            AtomicBoolean writerHoldsLock = new AtomicBoolean(false);
            AtomicBoolean secondWriterBlocked = new AtomicBoolean(false);
            CountDownLatch writerAcquired = new CountDownLatch(1);
            CountDownLatch secondAttempted = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);

            // First writer acquires
            assertTrue(writeLock.tryLock(5, TimeUnit.SECONDS));
            writerHoldsLock.set(true);
            writerAcquired.countDown();

            // Second writer tries to acquire (should fail quickly)
            new Thread(() -> {
                try {
                    writerAcquired.await();
                    Lock secondWrite = rwLock.writeLock();
                    boolean acquired = secondWrite.tryLock(500, TimeUnit.MILLISECONDS);
                    secondWriterBlocked.set(!acquired);
                    secondAttempted.countDown();
                    if (acquired) {
                        secondWrite.unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();

            assertTrue(secondAttempted.await(5, TimeUnit.SECONDS));
            assertTrue(secondWriterBlocked.get(), "Second writer should be blocked");

            writeLock.unlock();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        }
    }

    // ========== Reader/Writer Blocking ==========

    @Test
    void readerShouldBeBlockedByWriter() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockReadWriteLock rwLock = manager.createReadWriteLock("reader-blocked-by-writer");
            Lock writeLock = rwLock.writeLock();
            AtomicBoolean readerBlocked = new AtomicBoolean(false);
            CountDownLatch writerAcquired = new CountDownLatch(1);
            CountDownLatch readerAttempted = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);

            // Writer acquires first
            assertTrue(writeLock.tryLock(5, TimeUnit.SECONDS));
            writerAcquired.countDown();

            // Reader tries to acquire (should fail with short timeout)
            new Thread(() -> {
                try {
                    writerAcquired.await();
                    Lock readLock = rwLock.readLock();
                    boolean acquired = readLock.tryLock(500, TimeUnit.MILLISECONDS);
                    readerBlocked.set(!acquired);
                    readerAttempted.countDown();
                    if (acquired) {
                        readLock.unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();

            assertTrue(readerAttempted.await(5, TimeUnit.SECONDS));
            assertTrue(readerBlocked.get(), "Reader should be blocked by writer");

            writeLock.unlock();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void writerShouldWaitForReadersToFinish() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockReadWriteLock rwLock = manager.createReadWriteLock("writer-waits-readers");
            Lock readLock = rwLock.readLock();
            AtomicBoolean writerAcquiredAfterReaderRelease = new AtomicBoolean(false);
            CountDownLatch readerAcquired = new CountDownLatch(1);
            CountDownLatch writerStarted = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);

            // Reader acquires first
            assertTrue(readLock.tryLock(5, TimeUnit.SECONDS));
            readerAcquired.countDown();

            // Writer tries to acquire in background
            new Thread(() -> {
                try {
                    readerAcquired.await();
                    writerStarted.countDown();
                    Lock writeLock = rwLock.writeLock();
                    boolean acquired = writeLock.tryLock(10, TimeUnit.SECONDS);
                    writerAcquiredAfterReaderRelease.set(acquired);
                    if (acquired) {
                        writeLock.unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();

            assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(200); // Give writer time to attempt

            // Release read lock - writer should then succeed
            readLock.unlock();

            assertTrue(done.await(15, TimeUnit.SECONDS));
            assertTrue(writerAcquiredAfterReaderRelease.get(), "Writer should acquire after reader releases");
        }
    }

    // ========== Concurrent Access ==========

    @Test
    void shouldHandleConcurrentReadersAndWriters() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RedlockReadWriteLock rwLock = manager.createReadWriteLock("concurrent-rw");
            int readerCount = 5;
            int writerCount = 3;
            int totalThreads = readerCount + writerCount;
            AtomicInteger readAcquisitions = new AtomicInteger(0);
            AtomicInteger writeAcquisitions = new AtomicInteger(0);
            CountDownLatch startSignal = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(totalThreads);

            // Start readers
            for (int i = 0; i < readerCount; i++) {
                new Thread(() -> {
                    try {
                        startSignal.await();
                        Lock readLock = rwLock.readLock();
                        if (readLock.tryLock(10, TimeUnit.SECONDS)) {
                            try {
                                readAcquisitions.incrementAndGet();
                                Thread.sleep(50);
                            } finally {
                                readLock.unlock();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }).start();
            }

            // Start writers
            for (int i = 0; i < writerCount; i++) {
                new Thread(() -> {
                    try {
                        startSignal.await();
                        Lock writeLock = rwLock.writeLock();
                        if (writeLock.tryLock(10, TimeUnit.SECONDS)) {
                            try {
                                writeAcquisitions.incrementAndGet();
                                Thread.sleep(50);
                            } finally {
                                writeLock.unlock();
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
            assertTrue(readAcquisitions.get() > 0, "Should have successful read acquisitions");
            assertTrue(writeAcquisitions.get() > 0, "Should have successful write acquisitions");
        }
    }

    // ========== Lettuce Driver ==========

    @Test
    void shouldWorkWithLettuceDriver() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withLettuce(testConfiguration)) {
            RedlockReadWriteLock rwLock = manager.createReadWriteLock("lettuce-rwlock");

            Lock readLock = rwLock.readLock();
            assertTrue(readLock.tryLock(5, TimeUnit.SECONDS));
            readLock.unlock();

            Lock writeLock = rwLock.writeLock();
            assertTrue(writeLock.tryLock(5, TimeUnit.SECONDS));
            writeLock.unlock();
        }
    }
}
