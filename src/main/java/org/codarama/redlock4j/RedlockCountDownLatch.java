/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j;

import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.codarama.redlock4j.driver.RedisDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * A distributed countdown latch that allows one or more threads to wait until a set of operations being performed in
 * other threads completes. This is the distributed equivalent of {@link java.util.concurrent.CountDownLatch}.
 * 
 * <p>
 * <b>Key Features:</b>
 * </p>
 * <ul>
 * <li>Initialized with a count value</li>
 * <li>Threads can wait for the count to reach zero</li>
 * <li>Other threads decrement the count by calling countDown()</li>
 * <li>Once the count reaches zero, all waiting threads are released</li>
 * <li>The count cannot be reset (one-time use)</li>
 * </ul>
 * 
 * <p>
 * <b>Use Cases:</b>
 * </p>
 * <ul>
 * <li>Coordinating startup: Wait for all services to initialize</li>
 * <li>Batch processing: Wait for all workers to complete</li>
 * <li>Testing: Synchronize test threads</li>
 * <li>Distributed workflows: Coordinate multi-stage processes</li>
 * </ul>
 * 
 * <p>
 * <b>Example Usage:</b>
 * </p>
 * 
 * <pre>
 * {
 *     &#64;code
 *     // Create a latch that waits for 3 operations
 *     RedlockCountDownLatch latch = new RedlockCountDownLatch("startup", 3, redisDrivers, config);
 * 
 *     // Worker threads
 *     new Thread(() -> {
 *         initializeService1();
 *         latch.countDown(); // Decrement count
 *     }).start();
 * 
 *     new Thread(() -> {
 *         initializeService2();
 *         latch.countDown(); // Decrement count
 *     }).start();
 * 
 *     new Thread(() -> {
 *         initializeService3();
 *         latch.countDown(); // Decrement count
 *     }).start();
 * 
 *     // Main thread waits for all services
 *     latch.await(); // Blocks until count reaches 0
 *     System.out.println("All services initialized!");
 * }
 * </pre>
 */
public class RedlockCountDownLatch {
    private static final Logger logger = LoggerFactory.getLogger(RedlockCountDownLatch.class);

    private final String latchKey;
    private final String channelKey;
    private final int initialCount;
    private final List<RedisDriver> redisDrivers;
    private final RedlockConfiguration config;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private volatile CountDownLatch localLatch;

    /**
     * Creates a new distributed countdown latch.
     * 
     * @param latchKey
     *            the key for this latch
     * @param count
     *            the initial count (must be positive)
     * @param redisDrivers
     *            the Redis drivers to use
     * @param config
     *            the Redlock configuration
     */
    public RedlockCountDownLatch(String latchKey, int count, List<RedisDriver> redisDrivers,
            RedlockConfiguration config) {
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative");
        }

        this.latchKey = latchKey;
        this.channelKey = latchKey + ":channel";
        this.initialCount = count;
        this.redisDrivers = redisDrivers;
        this.config = config;
        this.localLatch = new CountDownLatch(1);

        // Initialize the latch count in Redis
        initializeLatch(count);

        logger.debug("Created RedlockCountDownLatch {} with count {}", latchKey, count);
    }

    /**
     * Causes the current thread to wait until the latch has counted down to zero.
     * 
     * <p>
     * If the current count is zero then this method returns immediately.
     * </p>
     * 
     * <p>
     * If the current count is greater than zero then the current thread becomes disabled for thread scheduling purposes
     * and lies dormant until the count reaches zero due to invocations of the {@link #countDown} method.
     * </p>
     * 
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     */
    public void await() throws InterruptedException {
        await(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    }

    /**
     * Causes the current thread to wait until the latch has counted down to zero, unless the specified waiting time
     * elapses.
     * 
     * @param timeout
     *            the maximum time to wait
     * @param unit
     *            the time unit of the timeout
     * @return true if the count reached zero, false if the timeout elapsed
     * @throws InterruptedException
     *             if the current thread is interrupted while waiting
     */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        // Subscribe to notifications if not already subscribed
        subscribeToNotifications();

        // Check if count is already zero
        long currentCount = getCount();
        if (currentCount <= 0) {
            logger.debug("Latch {} count already at zero", latchKey);
            return true;
        }

        // Wait on local latch with timeout (will be released by pub/sub notification)
        boolean completed = localLatch.await(timeout, unit);

        if (completed) {
            logger.debug("Latch {} count reached zero via notification", latchKey);
        } else {
            logger.debug("Latch {} await timeout elapsed", latchKey);
        }

        return completed;
    }

    /**
     * Decrements the count of the latch, releasing all waiting threads if the count reaches zero.
     *
     * <p>
     * If the current count is greater than zero then it is decremented. If the new count is zero then all waiting
     * threads are re-enabled for thread scheduling purposes.
     * </p>
     *
     * <p>
     * If the current count equals zero then nothing happens.
     * </p>
     */
    public void countDown() {
        int quorum = config.getQuorum();
        CountDownLatch quorumLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        // Execute atomic decrement + conditional publish on all nodes in parallel
        for (RedisDriver driver : redisDrivers) {
            CompletableFuture.runAsync(() -> {
                try {
                    // Atomic: decrement and publish if zero in single Lua script
                    long count = driver.decrAndPublishIfZero(latchKey, channelKey, "zero");
                    logger.debug("Decremented latch {} count to {} on {}", latchKey, count, driver.getIdentifier());
                    if (successCount.incrementAndGet() >= quorum) {
                        quorumLatch.countDown(); // Signal quorum reached
                    }
                } catch (Exception e) {
                    logger.debug("Failed to decrement latch count on {}: {}", driver.getIdentifier(), e.getMessage());
                }
            });
        }

        // Wait for quorum (not all nodes)
        try {
            quorumLatch.await();
            logger.debug("Successfully decremented latch {} count on quorum", latchKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while decrementing latch {}", latchKey);
        }
    }

    /**
     * Returns the current count.
     *
     * <p>
     * This method is typically used for debugging and testing purposes.
     * </p>
     *
     * @return the current count
     */
    public long getCount() {
        int quorum = config.getQuorum();
        CountDownLatch quorumLatch = new CountDownLatch(1);
        List<Long> results = new ArrayList<>();

        // Execute GET on all nodes in parallel
        for (RedisDriver driver : redisDrivers) {
            CompletableFuture.runAsync(() -> {
                try {
                    String countStr = driver.get(latchKey);
                    if (countStr != null) {
                        synchronized (results) {
                            results.add(Long.parseLong(countStr));
                            if (results.size() >= quorum) {
                                quorumLatch.countDown(); // Signal quorum reached
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to read latch count from {}: {}", driver.getIdentifier(), e.getMessage());
                }
            });
        }

        // Wait for quorum (not all nodes)
        try {
            quorumLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while reading latch {} count", latchKey);
            return 0;
        }

        synchronized (results) {
            if (results.size() >= quorum) {
                // Return average count
                long totalCount = results.stream().mapToLong(Long::longValue).sum();
                long avgCount = totalCount / results.size();
                return Math.max(0, avgCount); // Never return negative
            }
        }

        logger.warn("Failed to read latch {} count from quorum of nodes", latchKey);
        return 0; // Conservative fallback - assume completed
    }

    /**
     * Initializes the latch count in Redis using parallel operations with early quorum return.
     */
    private void initializeLatch(int count) {
        String countValue = String.valueOf(count);
        long expirationMs = config.getDefaultLockTimeoutMs() * 10;
        int quorum = config.getQuorum();

        // Latch to signal when quorum is reached
        CountDownLatch quorumLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        // Execute initialization on all nodes in parallel
        List<CompletableFuture<Boolean>> futures = new ArrayList<>(redisDrivers.size());
        for (RedisDriver driver : redisDrivers) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    driver.setex(latchKey, countValue, expirationMs);
                    if (successCount.incrementAndGet() >= quorum) {
                        quorumLatch.countDown(); // Signal quorum reached
                    }
                    return true;
                } catch (Exception e) {
                    logger.warn("Failed to initialize latch on {}: {}", driver.getIdentifier(), e.getMessage());
                    return false;
                }
            }));
        }

        // Wait for quorum (not all nodes)
        try {
            quorumLatch.await();
            logger.debug("Successfully initialized latch {} with count {} on quorum", latchKey, count);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while initializing latch {}", latchKey);
        }
    }

    /**
     * Subscribes to pub/sub notifications for when the latch reaches zero.
     */
    private void subscribeToNotifications() {
        if (subscribed.compareAndSet(false, true)) {
            // Start subscription in a separate thread
            new Thread(() -> {
                try {
                    // Subscribe to the first available driver
                    // In production, you might want to subscribe to multiple drivers for redundancy
                    if (!redisDrivers.isEmpty()) {
                        RedisDriver driver = redisDrivers.get(0);
                        driver.subscribe(new RedisDriver.MessageHandler() {
                            @Override
                            public void onMessage(String channel, String message) {
                                if ("zero".equals(message)) {
                                    logger.debug("Received zero notification for latch {}", latchKey);
                                    localLatch.countDown();
                                }
                            }

                            @Override
                            public void onError(Throwable error) {
                                logger.warn("Error in latch {} subscription: {}", latchKey, error.getMessage());
                            }
                        }, channelKey);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to subscribe to latch {} notifications: {}", latchKey, e.getMessage());
                    subscribed.set(false);
                }
            }, "RedlockCountDownLatch-Subscriber-" + latchKey).start();

            logger.debug("Started subscription for latch {} notifications", latchKey);
        }
    }

    /**
     * Resets the latch to its initial count.
     *
     * <p>
     * <b>Warning:</b> This is not part of the standard CountDownLatch API and should be used with caution. It's
     * provided for scenarios where you need to reuse a latch.
     * </p>
     *
     * <p>
     * This operation is not atomic and may lead to race conditions if called while other threads are waiting or
     * counting down.
     * </p>
     */
    public void reset() {
        logger.debug("Resetting latch {} to initial count {}", latchKey, initialCount);

        // Delete the existing latch using parallel DEL
        List<CompletableFuture<Void>> futures = new ArrayList<>(redisDrivers.size());
        for (RedisDriver driver : redisDrivers) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    driver.del(latchKey);
                } catch (Exception e) {
                    logger.warn("Failed to delete latch on {}: {}", driver.getIdentifier(), e.getMessage());
                }
            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Reset the local latch
        localLatch = new CountDownLatch(1);
        subscribed.set(false);

        // Reinitialize with the original count
        initializeLatch(initialCount);
    }

    /**
     * Queries if any threads are waiting on this latch.
     * 
     * <p>
     * Note: In a distributed environment, this is an approximation and may not be accurate due to network delays and
     * the distributed nature of the system.
     * </p>
     * 
     * @return true if there may be threads waiting, false otherwise
     */
    public boolean hasQueuedThreads() {
        // In a distributed system, we can't reliably determine this
        // Return true if count > 0 as a heuristic
        return getCount() > 0;
    }

    @Override
    public String toString() {
        return "RedlockCountDownLatch{" + "latchKey='" + latchKey + '\'' + ", count=" + getCount() + ", initialCount="
                + initialCount + '}';
    }
}
