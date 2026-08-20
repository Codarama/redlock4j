/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.strategy;

import org.codarama.redlock4j.LockResult;
import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.codarama.redlock4j.driver.RedisDriver;
import org.codarama.redlock4j.driver.RedisDriverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Lock execution strategy for multi-node Redis deployments using the Redlock algorithm.
 * 
 * <p>
 * This strategy implements distributed consensus:
 * </p>
 * <ul>
 * <li>Quorum-based lock acquisition (N/2 + 1 nodes must agree)</li>
 * <li>Clock drift compensation for distributed safety</li>
 * <li>Atomic release on all nodes</li>
 * </ul>
 *
 * @since 1.0
 * @author Tihomir Mateev
 */
public class MultiNodeStrategy implements LockExecutionStrategy {

    private static final Logger logger = LoggerFactory.getLogger(MultiNodeStrategy.class);

    /**
     * Shared executor for fanning out per-node Redis operations in parallel. Cached pool of daemon threads with idle
     * timeout so it scales with concurrent acquisition load without blocking JVM shutdown.
     */
    private static final Executor FANOUT_EXECUTOR = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "redlock4j-multinode-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    private final List<RedisDriver> drivers;
    private final int quorum;
    private final double clockDriftFactor;

    /**
     * Creates a multi-node strategy with the given drivers and configuration.
     *
     * @param drivers
     *            the list of Redis drivers for all nodes
     * @param config
     *            the Redlock configuration
     */
    public MultiNodeStrategy(List<RedisDriver> drivers, RedlockConfiguration config) {
        if (drivers == null || drivers.isEmpty()) {
            throw new IllegalArgumentException("At least one driver is required");
        }
        if (config == null) {
            throw new IllegalArgumentException("Configuration cannot be null");
        }
        this.drivers = drivers;
        this.quorum = config.getQuorum();
        this.clockDriftFactor = config.getClockDriftFactor();
    }

    @Override
    public LockResult acquireLock(String key, String value, long timeoutMs) {
        Instant startTime = Instant.now();

        int successfulNodes = fanOut("acquire lock", driver -> driver.setIfNotExists(key, value, timeoutMs));

        Duration elapsed = Duration.between(startTime, Instant.now());
        long validityTime = calculateValidityTime(timeoutMs, elapsed.toMillis());

        boolean acquired = successfulNodes >= quorum && validityTime > 0;

        if (!acquired) {
            // Release any partial locks
            releaseLock(key, value);
            logger.debug("Failed to acquire lock: key={}, successfulNodes={}/{}, quorum={}", key, successfulNodes,
                    drivers.size(), quorum);
            return LockResult.NOT_ACQUIRED;
        }

        logger.debug("Lock acquired: key={}, successfulNodes={}/{}, validityTime={}ms", key, successfulNodes,
                drivers.size(), validityTime);
        return new LockResult(true, validityTime, value, successfulNodes, drivers.size());
    }

    @Override
    public void releaseLock(String key, String value) {
        fanOut("release lock", driver -> {
            driver.deleteIfValueMatches(key, value);
            return Boolean.TRUE;
        });
        logger.debug("Lock released on all nodes: key={}", key);
    }

    @Override
    public boolean extendLock(String key, String currentValue, long newTimeoutMs) {
        Instant startTime = Instant.now();

        int successfulNodes = fanOut("extend lock",
                driver -> driver.setIfValueMatches(key, currentValue, currentValue, newTimeoutMs));

        Duration elapsed = Duration.between(startTime, Instant.now());
        long validityTime = calculateValidityTime(newTimeoutMs, elapsed.toMillis());

        boolean extended = successfulNodes >= quorum && validityTime > 0;
        logger.debug("Lock extension {}: key={}, successfulNodes={}/{}", extended ? "succeeded" : "failed", key,
                successfulNodes, drivers.size());
        return extended;
    }

    @Override
    public long calculateValidityTime(long timeoutMs, long elapsedMs) {
        // Account for clock drift between Redis servers
        long driftTime = (long) (timeoutMs * clockDriftFactor) + 2;
        return timeoutMs - elapsedMs - driftTime;
    }

    @Override
    public int executeOnNodes(Function<RedisDriver, Boolean> operation) {
        return fanOut("execute operation", operation::apply);
    }

    /**
     * Fans an operation out across all drivers in parallel and returns the count of nodes where the operation returned
     * {@code Boolean.TRUE}. Per-node exceptions are logged and counted as failures so a single slow or down node cannot
     * block the quorum.
     */
    private int fanOut(String description, FanOutOperation operation) {
        List<CompletableFuture<Boolean>> futures = new ArrayList<>(drivers.size());
        for (RedisDriver driver : drivers) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return Boolean.TRUE.equals(operation.apply(driver));
                } catch (Exception e) {
                    logger.warn("Failed to {} on {}: {}", description, driver.getIdentifier(), e.getMessage());
                    return Boolean.FALSE;
                }
            }, FANOUT_EXECUTOR));
        }

        int successCount = 0;
        for (CompletableFuture<Boolean> future : futures) {
            try {
                if (Boolean.TRUE.equals(future.join())) {
                    successCount++;
                }
            } catch (CompletionException e) {
                logger.warn("Unexpected error during {}: {}", description, e.getMessage());
            }
        }
        return successCount;
    }

    /**
     * Functional interface for per-node operations that may throw checked Redis driver exceptions.
     */
    @FunctionalInterface
    private interface FanOutOperation {
        Boolean apply(RedisDriver driver) throws RedisDriverException;
    }

    @Override
    public boolean isSuccessful(int successCount) {
        return successCount >= quorum;
    }

    @Override
    public boolean isSingleNodeMode() {
        return false;
    }
}
