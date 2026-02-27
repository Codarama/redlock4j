/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.examples;

import org.codarama.redlock4j.*;
import org.codarama.redlock4j.configuration.RedlockConfiguration;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * Example demonstrating advanced locking primitives in Redlock4j: - FairLock: FIFO ordering for lock acquisition -
 * MultiLock: Atomic multi-resource locking - ReadWriteLock: Separate read/write locks - Semaphore: Distributed
 * semaphore with permits - CountDownLatch: Distributed countdown latch
 */
public class AdvancedLockingExample {

    public static void main(String[] args) throws Exception {
        // Configure Redis nodes
        RedlockConfiguration config = RedlockConfiguration.builder().addRedisNode("localhost", 6379)
                .addRedisNode("localhost", 6380).addRedisNode("localhost", 6381)
                .defaultLockTimeout(Duration.ofSeconds(30)).retryDelay(Duration.ofMillis(200)).maxRetryAttempts(3)
                .lockAcquisitionTimeout(Duration.ofSeconds(10)).build();

        try (RedlockManager manager = RedlockManager.withJedis(config)) {

            if (!manager.isHealthy()) {
                System.err.println("RedlockManager is not healthy");
                return;
            }

            System.out.println("=== Advanced Locking Primitives Demo ===\n");

            // 1. Fair Lock Example
            demonstrateFairLock(manager);

            // 2. Multi-Lock Example
            demonstrateMultiLock(manager);

            // 3. Read-Write Lock Example
            demonstrateReadWriteLock(manager);

            // 4. Semaphore Example
            demonstrateSemaphore(manager);

            // 5. CountDownLatch Example
            demonstrateCountDownLatch(manager);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Demonstrates Fair Lock with FIFO ordering.
     */
    private static void demonstrateFairLock(RedlockManager manager) throws InterruptedException {
        System.out.println("1. FAIR LOCK EXAMPLE");
        System.out.println("   Fair locks ensure FIFO ordering for lock acquisition\n");

        Lock fairLock = manager.createFairLock("fair-resource");

        // Simulate multiple threads competing for the lock
        Thread t1 = new Thread(() -> {
            try {
                System.out.println("   Thread 1: Requesting fair lock...");
                fairLock.lock();
                try {
                    System.out.println("   Thread 1: Acquired fair lock (should be first)");
                    Thread.sleep(1000);
                } finally {
                    fairLock.unlock();
                    System.out.println("   Thread 1: Released fair lock");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                Thread.sleep(100); // Start slightly after t1
                System.out.println("   Thread 2: Requesting fair lock...");
                fairLock.lock();
                try {
                    System.out.println("   Thread 2: Acquired fair lock (should be second)");
                    Thread.sleep(1000);
                } finally {
                    fairLock.unlock();
                    System.out.println("   Thread 2: Released fair lock");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        System.out.println();
    }

    /**
     * Demonstrates Multi-Lock for atomic multi-resource locking.
     */
    private static void demonstrateMultiLock(RedlockManager manager) throws InterruptedException {
        System.out.println("2. MULTI-LOCK EXAMPLE");
        System.out.println("   Multi-locks allow atomic acquisition of multiple resources\n");

        // Lock multiple accounts atomically to prevent deadlocks
        Lock multiLock = manager.createMultiLock(Arrays.asList("account:1", "account:2", "account:3"));

        System.out.println("   Attempting to lock 3 accounts atomically...");
        if (multiLock.tryLock(5, TimeUnit.SECONDS)) {
            try {
                System.out.println("   ✓ All 3 accounts locked successfully");
                System.out.println("   Performing transfer between accounts...");
                Thread.sleep(1000);
                System.out.println("   Transfer completed");
            } finally {
                multiLock.unlock();
                System.out.println("   ✓ All 3 accounts unlocked");
            }
        } else {
            System.out.println("   ✗ Failed to acquire all locks");
        }

        System.out.println();
    }

    /**
     * Demonstrates Read-Write Lock for concurrent reads.
     */
    private static void demonstrateReadWriteLock(RedlockManager manager) throws InterruptedException {
        System.out.println("3. READ-WRITE LOCK EXAMPLE");
        System.out.println("   Multiple readers can access simultaneously, writers get exclusive access\n");

        RedlockReadWriteLock rwLock = manager.createReadWriteLock("shared-data");

        // Start multiple reader threads
        Thread reader1 = new Thread(() -> {
            try {
                System.out.println("   Reader 1: Acquiring read lock...");
                rwLock.readLock().lock();
                try {
                    System.out.println("   Reader 1: Reading data...");
                    Thread.sleep(1000);
                    System.out.println("   Reader 1: Done reading");
                } finally {
                    rwLock.readLock().unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread reader2 = new Thread(() -> {
            try {
                Thread.sleep(100);
                System.out.println("   Reader 2: Acquiring read lock...");
                rwLock.readLock().lock();
                try {
                    System.out.println("   Reader 2: Reading data (concurrent with Reader 1)...");
                    Thread.sleep(1000);
                    System.out.println("   Reader 2: Done reading");
                } finally {
                    rwLock.readLock().unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(200);
                System.out.println("   Writer: Waiting for read locks to release...");
                rwLock.writeLock().lock();
                try {
                    System.out.println("   Writer: Writing data (exclusive access)...");
                    Thread.sleep(1000);
                    System.out.println("   Writer: Done writing");
                } finally {
                    rwLock.writeLock().unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        reader1.start();
        reader2.start();
        writer.start();

        reader1.join();
        reader2.join();
        writer.join();

        System.out.println();
    }

    /**
     * Demonstrates Semaphore for rate limiting.
     */
    private static void demonstrateSemaphore(RedlockManager manager) throws InterruptedException {
        System.out.println("4. SEMAPHORE EXAMPLE");
        System.out.println("   Semaphores limit concurrent access to a resource\n");

        // Create a semaphore with 2 permits (max 2 concurrent accesses)
        RedlockSemaphore semaphore = manager.createSemaphore("api-limiter", 2);

        System.out.println("   Created semaphore with 2 permits");
        System.out.println("   Starting 4 threads that need permits...\n");

        for (int i = 1; i <= 4; i++) {
            final int threadNum = i;
            new Thread(() -> {
                try {
                    System.out.println("   Thread " + threadNum + ": Requesting permit...");
                    if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                        try {
                            System.out.println("   Thread " + threadNum + ": ✓ Acquired permit, making API call...");
                            Thread.sleep(1000);
                            System.out.println("   Thread " + threadNum + ": API call completed");
                        } finally {
                            semaphore.release();
                            System.out.println("   Thread " + threadNum + ": Released permit");
                        }
                    } else {
                        System.out.println("   Thread " + threadNum + ": ✗ Failed to acquire permit");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();

            Thread.sleep(100); // Stagger thread starts
        }

        Thread.sleep(5000); // Wait for all threads to complete
        System.out.println();
    }

    /**
     * Demonstrates CountDownLatch for coordinating startup.
     */
    private static void demonstrateCountDownLatch(RedlockManager manager) throws InterruptedException {
        System.out.println("5. COUNTDOWN LATCH EXAMPLE");
        System.out.println("   Countdown latches coordinate multi-stage processes\n");

        // Create a latch that waits for 3 services to initialize
        RedlockCountDownLatch latch = manager.createCountDownLatch("startup-latch", 3);

        System.out.println("   Waiting for 3 services to initialize...\n");

        // Start 3 service initialization threads
        for (int i = 1; i <= 3; i++) {
            final int serviceNum = i;
            new Thread(() -> {
                try {
                    System.out.println("   Service " + serviceNum + ": Initializing...");
                    Thread.sleep(1000 * serviceNum); // Simulate varying init times
                    System.out.println("   Service " + serviceNum + ": ✓ Initialized");
                    latch.countDown();
                    System.out.println(
                            "   Service " + serviceNum + ": Counted down (remaining: " + latch.getCount() + ")");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }

        // Main thread waits for all services
        System.out.println("   Main: Waiting for all services...");
        boolean completed = latch.await(10, TimeUnit.SECONDS);

        if (completed) {
            System.out.println("   Main: ✓ All services initialized! Application ready.");
        } else {
            System.out.println("   Main: ✗ Timeout waiting for services");
        }

        System.out.println();
    }
}
