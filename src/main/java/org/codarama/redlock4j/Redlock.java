/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j;

import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.codarama.redlock4j.driver.RedisDriver;
import org.codarama.redlock4j.driver.RedisDriverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * Implementation of the Redlock distributed locking algorithm that implements Java's Lock interface.
 */
public class Redlock implements Lock {
    private static final Logger logger = LoggerFactory.getLogger(Redlock.class);

    private final String lockKey;
    private final List<RedisDriver> redisDrivers;
    private final RedlockConfiguration config;
    private final SecureRandom secureRandom;

    // Thread-local storage for lock state
    private final ThreadLocal<LockState> lockState = new ThreadLocal<>();

    private static class LockState {
        final String lockValue;
        final long acquisitionTime;
        final long validityTime;
        int holdCount; // For reentrancy

        LockState(String lockValue, long acquisitionTime, long validityTime) {
            this.lockValue = lockValue;
            this.acquisitionTime = acquisitionTime;
            this.validityTime = validityTime;
            this.holdCount = 1; // Initial acquisition
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

    public Redlock(String lockKey, List<RedisDriver> redisDrivers, RedlockConfiguration config) {
        this.lockKey = lockKey;
        this.redisDrivers = redisDrivers;
        this.config = config;
        this.secureRandom = new SecureRandom();
    }

    @Override
    public void lock() {
        try {
            if (!tryLock(config.getLockAcquisitionTimeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new RedlockException("Failed to acquire lock within timeout: " + lockKey);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RedlockException("Interrupted while acquiring lock: " + lockKey, e);
        }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        if (!tryLock(config.getLockAcquisitionTimeoutMs(), TimeUnit.MILLISECONDS)) {
            throw new RedlockException("Failed to acquire lock within timeout: " + lockKey);
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
            logger.debug("Reentrant lock acquisition for {} (hold count: {})", lockKey, currentState.holdCount);
            return true;
        }

        long timeoutMs = unit.toMillis(time);
        long startTime = System.currentTimeMillis();

        for (int attempt = 0; attempt <= config.getMaxRetryAttempts(); attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }

            LockResult result = attemptLock();
            if (result.isAcquired()) {
                lockState.set(
                        new LockState(result.getLockValue(), System.currentTimeMillis(), result.getValidityTimeMs()));
                logger.debug("Successfully acquired lock {} on attempt {}", lockKey, attempt + 1);
                return true;
            }

            // Check if we've exceeded the timeout
            if (timeoutMs > 0 && (System.currentTimeMillis() - startTime) >= timeoutMs) {
                logger.debug("Lock acquisition timeout exceeded for {}", lockKey);
                break;
            }

            // Wait before retrying (except on the last attempt)
            if (attempt < config.getMaxRetryAttempts()) {
                long delay = config.getRetryDelayMs() + ThreadLocalRandom.current().nextLong(config.getRetryDelayMs());
                Thread.sleep(delay);
            }
        }

        logger.debug("Failed to acquire lock {} after {} attempts", lockKey, config.getMaxRetryAttempts() + 1);
        return false;
    }

    private LockResult attemptLock() {
        String lockValue = generateLockValue();
        long startTime = System.currentTimeMillis();
        int successfulNodes = 0;

        // Try to acquire the lock on all nodes
        for (RedisDriver driver : redisDrivers) {
            try {
                if (driver.setIfNotExists(lockKey, lockValue, config.getDefaultLockTimeoutMs())) {
                    successfulNodes++;
                }
            } catch (RedisDriverException e) {
                logger.warn("Failed to acquire lock on {}: {}", driver.getIdentifier(), e.getMessage());
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        long driftTime = (long) (config.getDefaultLockTimeoutMs() * config.getClockDriftFactor()) + 2;
        long validityTime = config.getDefaultLockTimeoutMs() - elapsedTime - driftTime;

        boolean acquired = successfulNodes >= config.getQuorum() && validityTime > 0;

        if (!acquired) {
            // Release any locks we managed to acquire
            releaseLock(lockValue);
        }

        return new LockResult(acquired, validityTime, lockValue, successfulNodes, redisDrivers.size());
    }

    @Override
    public void unlock() {
        LockState state = lockState.get();
        if (state == null) {
            logger.warn("Attempting to unlock {} but no lock state found for current thread", lockKey);
            return;
        }

        if (!state.isValid()) {
            logger.warn("Lock {} has expired, cannot safely unlock", lockKey);
            lockState.remove();
            return;
        }

        // Handle reentrancy - only release when hold count reaches 0
        int remainingHolds = state.decrementHoldCount();
        if (remainingHolds > 0) {
            logger.debug("Reentrant unlock for {} (remaining holds: {})", lockKey, remainingHolds);
            return;
        }

        // Final unlock - release the distributed lock
        releaseLock(state.lockValue);
        lockState.remove();
        logger.debug("Successfully released lock {}", lockKey);
    }

    private void releaseLock(String lockValue) {
        for (RedisDriver driver : redisDrivers) {
            try {
                driver.deleteIfValueMatches(lockKey, lockValue);
            } catch (RedisDriverException e) {
                logger.warn("Failed to release lock on {}: {}", driver.getIdentifier(), e.getMessage());
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
        throw new UnsupportedOperationException("Conditions are not supported by distributed locks");
    }

    /**
     * Checks if the current thread holds this lock.
     * 
     * @return true if the current thread holds the lock and it's still valid
     */
    public boolean isHeldByCurrentThread() {
        LockState state = lockState.get();
        return state != null && state.isValid();
    }

    /**
     * Gets the remaining validity time of the lock for the current thread.
     *
     * @return remaining validity time in milliseconds, or 0 if not held or expired
     */
    public long getRemainingValidityTime() {
        LockState state = lockState.get();
        if (state == null) {
            return 0;
        }
        long remaining = state.acquisitionTime + state.validityTime - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    /**
     * Gets the hold count for the current thread. This indicates how many times the current thread has acquired this
     * lock.
     *
     * @return hold count, or 0 if not held by current thread
     */
    public int getHoldCount() {
        LockState state = lockState.get();
        return state != null && state.isValid() ? state.holdCount : 0;
    }

    /**
     * Extends the validity time of the lock held by the current thread.
     * <p>
     * This method attempts to extend the lock on a quorum of Redis nodes using the same lock value. The extension is
     * only successful if:
     * <ul>
     * <li>The current thread holds a valid lock</li>
     * <li>The extension succeeds on at least a quorum (N/2+1) of nodes</li>
     * <li>The new validity time (after accounting for clock drift) is positive</li>
     * </ul>
     * <p>
     * <b>Important limitations:</b>
     * <ul>
     * <li>Lock extension is for efficiency, not correctness</li>
     * <li>Should not be used as a substitute for proper timeout configuration</li>
     * </ul>
     *
     * @param additionalTimeMs
     *            additional time in milliseconds to extend the lock
     * @return true if the lock was successfully extended on a quorum of nodes, false otherwise
     * @throws IllegalArgumentException
     *             if additionalTimeMs is negative or zero
     */
    public boolean extend(long additionalTimeMs) {
        if (additionalTimeMs <= 0) {
            throw new IllegalArgumentException("Additional time must be positive");
        }

        LockState state = lockState.get();
        if (state == null || !state.isValid()) {
            logger.debug("Cannot extend lock {} - not held or expired", lockKey);
            return false;
        }

        long startTime = System.currentTimeMillis();
        int successfulNodes = 0;
        long newExpireTimeMs = config.getDefaultLockTimeoutMs() + additionalTimeMs;

        // Try to extend the lock on all nodes using CAS operation
        for (RedisDriver driver : redisDrivers) {
            try {
                // Use setIfValueMatches to atomically extend only if lock value matches
                if (driver.setIfValueMatches(lockKey, state.lockValue, state.lockValue, newExpireTimeMs)) {
                    successfulNodes++;
                }
            } catch (RedisDriverException e) {
                logger.warn("Failed to extend lock on {}: {}", driver.getIdentifier(), e.getMessage());
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        long driftTime = (long) (newExpireTimeMs * config.getClockDriftFactor()) + 2;
        long newValidityTime = newExpireTimeMs - elapsedTime - driftTime;

        boolean extended = successfulNodes >= config.getQuorum() && newValidityTime > 0;

        if (extended) {
            // Update local lock state with new validity time
            // Note: We create a new LockState to maintain immutability of timing fields
            LockState newState = new LockState(state.lockValue, System.currentTimeMillis(), newValidityTime);
            newState.holdCount = state.holdCount; // Preserve hold count
            lockState.set(newState);
            logger.debug("Successfully extended lock {} on {}/{} nodes (new validity: {}ms)", lockKey, successfulNodes,
                    redisDrivers.size(), newValidityTime);
        } else {
            logger.debug("Failed to extend lock {} - only {}/{} nodes succeeded (quorum: {})", lockKey, successfulNodes,
                    redisDrivers.size(), config.getQuorum());
        }

        return extended;
    }
}
