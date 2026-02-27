/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j;

import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.codarama.redlock4j.driver.RedisDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;

/**
 * A distributed multi-lock implementation that allows atomic acquisition of multiple resources. This prevents deadlocks
 * by always acquiring locks in a consistent order (lexicographically sorted by key).
 * 
 * <p>
 * The MultiLock is useful when you need to perform operations that span multiple resources and require exclusive access
 * to all of them simultaneously.
 * </p>
 * 
 * <p>
 * <b>Key Features:</b>
 * </p>
 * <ul>
 * <li>Atomic acquisition of multiple locks</li>
 * <li>Deadlock prevention through consistent ordering</li>
 * <li>All-or-nothing semantics: either all locks are acquired or none</li>
 * <li>Automatic cleanup on failure</li>
 * </ul>
 * 
 * <p>
 * <b>Example Usage:</b>
 * </p>
 * 
 * <pre>
 * {
 *     &#64;code
 *     MultiLock multiLock = new MultiLock(Arrays.asList("account:1", "account:2", "account:3"), redisDrivers, config);
 * 
 *     multiLock.lock();
 *     try {
 *         // All three accounts are now locked
 *         transferBetweenAccounts();
 *     } finally {
 *         multiLock.unlock();
 *     }
 * }
 * </pre>
 */
public class MultiLock implements Lock {
    private static final Logger logger = LoggerFactory.getLogger(MultiLock.class);

    private final List<String> lockKeys;
    private final List<RedisDriver> redisDrivers;
    private final RedlockConfiguration config;
    private final SecureRandom secureRandom;

    // Thread-local storage for lock state
    private final ThreadLocal<LockState> lockState = new ThreadLocal<>();

    private static class LockState {
        final Map<String, String> lockValues; // key -> lockValue
        final long acquisitionTime;
        final long validityTime;
        int holdCount;

        LockState(Map<String, String> lockValues, long acquisitionTime, long validityTime) {
            this.lockValues = new HashMap<>(lockValues);
            this.acquisitionTime = acquisitionTime;
            this.validityTime = validityTime;
            this.holdCount = 1;
        }

        boolean isValid() {
            return System.currentTimeMillis() < acquisitionTime + validityTime;
        }

        void incrementHoldCount() {
            holdCount++;
        }

        int decrementHoldCount() {
            return --holdCount;
        }
    }

    /**
     * Creates a new MultiLock for the specified resources.
     * 
     * @param lockKeys
     *            the keys to lock (will be sorted internally to prevent deadlocks)
     * @param redisDrivers
     *            the Redis drivers to use
     * @param config
     *            the Redlock configuration
     */
    public MultiLock(List<String> lockKeys, List<RedisDriver> redisDrivers, RedlockConfiguration config) {
        if (lockKeys == null || lockKeys.isEmpty()) {
            throw new IllegalArgumentException("Lock keys cannot be null or empty");
        }

        // Sort keys to ensure consistent ordering and prevent deadlocks
        this.lockKeys = lockKeys.stream().distinct().sorted().collect(Collectors.toList());

        this.redisDrivers = redisDrivers;
        this.config = config;
        this.secureRandom = new SecureRandom();

        logger.debug("Created MultiLock for {} resources: {}", this.lockKeys.size(), this.lockKeys);
    }

    @Override
    public void lock() {
        try {
            if (!tryLock(config.getLockAcquisitionTimeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new RedlockException("Failed to acquire multi-lock within timeout for keys: " + lockKeys);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RedlockException("Interrupted while acquiring multi-lock for keys: " + lockKeys, e);
        }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        if (!tryLock(config.getLockAcquisitionTimeoutMs(), TimeUnit.MILLISECONDS)) {
            throw new RedlockException("Failed to acquire multi-lock within timeout for keys: " + lockKeys);
        }
    }

    @Override
    public boolean tryLock() {
        try {
            return tryLock(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        // Check if current thread already holds the lock (reentrancy)
        LockState currentState = lockState.get();
        if (currentState != null && currentState.isValid()) {
            currentState.incrementHoldCount();
            logger.debug("Reentrant multi-lock acquisition for {} keys (hold count: {})", lockKeys.size(),
                    currentState.holdCount);
            return true;
        }

        long timeoutMs = unit.toMillis(time);
        long startTime = System.currentTimeMillis();

        for (int attempt = 0; attempt <= config.getMaxRetryAttempts(); attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }

            MultiLockResult result = attemptMultiLock();
            if (result.isAcquired()) {
                lockState.set(
                        new LockState(result.getLockValues(), System.currentTimeMillis(), result.getValidityTimeMs()));
                logger.debug("Successfully acquired multi-lock for {} keys on attempt {}", lockKeys.size(),
                        attempt + 1);
                return true;
            }

            // Check if we've exceeded the timeout
            if (timeoutMs > 0 && (System.currentTimeMillis() - startTime) >= timeoutMs) {
                logger.debug("Multi-lock acquisition timeout exceeded for keys: {}", lockKeys);
                break;
            }

            // Wait before retrying
            if (attempt < config.getMaxRetryAttempts()) {
                Thread.sleep(config.getRetryDelayMs());
            }
        }

        return false;
    }

    @Override
    public void unlock() {
        LockState state = lockState.get();
        if (state == null) {
            logger.warn("Attempting to unlock multi-lock but no lock state found for current thread");
            return;
        }

        if (!state.isValid()) {
            logger.warn("Multi-lock has expired, cannot safely unlock");
            lockState.remove();
            return;
        }

        // Handle reentrancy
        int remainingHolds = state.decrementHoldCount();
        if (remainingHolds > 0) {
            logger.debug("Reentrant unlock for multi-lock (remaining holds: {})", remainingHolds);
            return;
        }

        // Final unlock - release all locks
        releaseAllLocks(state.lockValues);
        lockState.remove();
        logger.debug("Successfully released multi-lock for {} keys", lockKeys.size());
    }

    /**
     * Attempts to acquire all locks atomically.
     */
    private MultiLockResult attemptMultiLock() {
        Map<String, String> lockValues = new HashMap<>();
        long startTime = System.currentTimeMillis();

        // Generate unique lock values for each key
        for (String key : lockKeys) {
            lockValues.put(key, generateLockValue());
        }

        // Try to acquire all locks on each Redis node
        int successfulNodes = 0;
        for (RedisDriver driver : redisDrivers) {
            if (acquireAllOnNode(driver, lockValues)) {
                successfulNodes++;
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        long driftTime = (long) (config.getDefaultLockTimeoutMs() * config.getClockDriftFactor()) + 2;
        long validityTime = config.getDefaultLockTimeoutMs() - elapsedTime - driftTime;

        boolean acquired = successfulNodes >= config.getQuorum() && validityTime > 0;

        if (!acquired) {
            // Release any locks we managed to acquire
            releaseAllLocks(lockValues);
        }

        return new MultiLockResult(acquired, validityTime, lockValues, successfulNodes, redisDrivers.size());
    }

    /**
     * Attempts to acquire all locks on a single Redis node.
     */
    private boolean acquireAllOnNode(RedisDriver driver, Map<String, String> lockValues) {
        List<String> acquiredKeys = new ArrayList<>();

        try {
            // Try to acquire each lock in order
            for (String key : lockKeys) {
                String lockValue = lockValues.get(key);
                if (driver.setIfNotExists(key, lockValue, config.getDefaultLockTimeoutMs())) {
                    acquiredKeys.add(key);
                } else {
                    // Failed to acquire this lock, rollback
                    rollbackOnNode(driver, lockValues, acquiredKeys);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            logger.debug("Failed to acquire multi-lock on {}: {}", driver.getIdentifier(), e.getMessage());
            rollbackOnNode(driver, lockValues, acquiredKeys);
            return false;
        }
    }

    /**
     * Rolls back locks acquired on a single node.
     */
    private void rollbackOnNode(RedisDriver driver, Map<String, String> lockValues, List<String> acquiredKeys) {
        for (String key : acquiredKeys) {
            try {
                driver.deleteIfValueMatches(key, lockValues.get(key));
            } catch (Exception e) {
                logger.warn("Failed to rollback lock {} on {}: {}", key, driver.getIdentifier(), e.getMessage());
            }
        }
    }

    /**
     * Releases all locks across all nodes.
     */
    private void releaseAllLocks(Map<String, String> lockValues) {
        for (RedisDriver driver : redisDrivers) {
            for (Map.Entry<String, String> entry : lockValues.entrySet()) {
                try {
                    driver.deleteIfValueMatches(entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    logger.warn("Failed to release lock {} on {}: {}", entry.getKey(), driver.getIdentifier(),
                            e.getMessage());
                }
            }
        }
    }

    private String generateLockValue() {
        byte[] bytes = new byte[20];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Override
    public Condition newCondition() {
        throw new UnsupportedOperationException("Conditions are not supported by distributed multi-locks");
    }

    /**
     * Result of a multi-lock acquisition attempt.
     */
    private static class MultiLockResult {
        private final boolean acquired;
        private final long validityTimeMs;
        private final Map<String, String> lockValues;
        private final int successfulNodes;
        private final int totalNodes;

        MultiLockResult(boolean acquired, long validityTimeMs, Map<String, String> lockValues, int successfulNodes,
                int totalNodes) {
            this.acquired = acquired;
            this.validityTimeMs = validityTimeMs;
            this.lockValues = lockValues;
            this.successfulNodes = successfulNodes;
            this.totalNodes = totalNodes;
        }

        boolean isAcquired() {
            return acquired;
        }

        long getValidityTimeMs() {
            return validityTimeMs;
        }

        Map<String, String> getLockValues() {
            return lockValues;
        }
    }
}
