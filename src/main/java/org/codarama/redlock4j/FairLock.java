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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * A distributed fair lock implementation that ensures FIFO (First-In-First-Out) ordering for lock acquisition. This
 * lock uses Redis sorted sets to maintain a queue of waiters, ensuring that threads acquire the lock in the order they
 * requested it.
 * 
 * <p>
 * The fair lock provides stronger ordering guarantees than the standard Redlock but may have slightly lower throughput
 * due to the additional coordination required.
 * </p>
 * 
 * <p>
 * <b>Implementation Details:</b>
 * </p>
 * <ul>
 * <li>Uses Redis sorted sets with timestamps to maintain FIFO order</li>
 * <li>Each waiter is assigned a unique token and timestamp</li>
 * <li>Only the waiter with the lowest timestamp can acquire the lock</li>
 * <li>Automatic cleanup of expired waiters</li>
 * </ul>
 */
public class FairLock implements Lock {
    private static final Logger logger = LoggerFactory.getLogger(FairLock.class);

    private final String lockKey;
    private final String queueKey;
    private final List<RedisDriver> redisDrivers;
    private final RedlockConfiguration config;
    private final SecureRandom secureRandom;

    // Thread-local storage for lock state
    private final ThreadLocal<LockState> lockState = new ThreadLocal<>();

    private static class LockState {
        final String lockValue;
        final String queueToken;
        final long acquisitionTime;
        final long validityTime;
        int holdCount;

        LockState(String lockValue, String queueToken, long acquisitionTime, long validityTime) {
            this.lockValue = lockValue;
            this.queueToken = queueToken;
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

    public FairLock(String lockKey, List<RedisDriver> redisDrivers, RedlockConfiguration config) {
        this.lockKey = lockKey;
        this.queueKey = lockKey + ":queue";
        this.redisDrivers = redisDrivers;
        this.config = config;
        this.secureRandom = new SecureRandom();
    }

    @Override
    public void lock() {
        try {
            if (!tryLock(config.getLockAcquisitionTimeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new RedlockException("Failed to acquire fair lock within timeout: " + lockKey);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RedlockException("Interrupted while acquiring fair lock: " + lockKey, e);
        }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        if (!tryLock(config.getLockAcquisitionTimeoutMs(), TimeUnit.MILLISECONDS)) {
            throw new RedlockException("Failed to acquire fair lock within timeout: " + lockKey);
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
            logger.debug("Reentrant fair lock acquisition for {} (hold count: {})", lockKey, currentState.holdCount);
            return true;
        }

        long timeoutMs = unit.toMillis(time);
        long startTime = System.currentTimeMillis();
        String queueToken = generateToken();
        long timestamp = System.currentTimeMillis();

        try {
            // Add ourselves to the queue
            addToQueue(queueToken, timestamp);

            for (int attempt = 0; attempt <= config.getMaxRetryAttempts(); attempt++) {
                if (Thread.currentThread().isInterrupted()) {
                    removeFromQueue(queueToken);
                    throw new InterruptedException();
                }

                // Check if we're at the front of the queue
                if (isAtFrontOfQueue(queueToken)) {
                    // Try to acquire the lock
                    LockResult result = attemptLock();
                    if (result.isAcquired()) {
                        lockState.set(new LockState(result.getLockValue(), queueToken, System.currentTimeMillis(),
                                result.getValidityTimeMs()));
                        logger.debug("Successfully acquired fair lock {} on attempt {}", lockKey, attempt + 1);
                        return true;
                    }
                }

                // Check if we've exceeded the timeout
                if (timeoutMs > 0 && (System.currentTimeMillis() - startTime) >= timeoutMs) {
                    logger.debug("Fair lock acquisition timeout exceeded for {}", lockKey);
                    removeFromQueue(queueToken);
                    break;
                }

                // Wait before retrying
                if (attempt < config.getMaxRetryAttempts()) {
                    Thread.sleep(config.getRetryDelayMs());
                }
            }

            removeFromQueue(queueToken);
            return false;
        } catch (InterruptedException e) {
            removeFromQueue(queueToken);
            throw e;
        }
    }

    @Override
    public void unlock() {
        LockState state = lockState.get();
        if (state == null) {
            logger.warn("Attempting to unlock {} but no lock state found for current thread", lockKey);
            return;
        }

        if (!state.isValid()) {
            logger.warn("Fair lock {} has expired, cannot safely unlock", lockKey);
            lockState.remove();
            removeFromQueue(state.queueToken);
            return;
        }

        // Handle reentrancy
        int remainingHolds = state.decrementHoldCount();
        if (remainingHolds > 0) {
            logger.debug("Reentrant unlock for {} (remaining holds: {})", lockKey, remainingHolds);
            return;
        }

        // Final unlock
        releaseLock(state.lockValue);
        removeFromQueue(state.queueToken);
        lockState.remove();
        logger.debug("Successfully released fair lock {}", lockKey);
    }

    /**
     * Adds a token to the queue with the given timestamp. Uses Redis sorted sets (ZADD) to maintain FIFO ordering.
     */
    private void addToQueue(String token, long timestamp) {
        int successfulNodes = 0;

        for (RedisDriver driver : redisDrivers) {
            try {
                // Add to sorted set with timestamp as score
                if (driver.zAdd(queueKey, timestamp, token)) {
                    successfulNodes++;
                }
            } catch (Exception e) {
                logger.debug("Failed to add to queue on {}: {}", driver.getIdentifier(), e.getMessage());
            }
        }

        // Clean up expired entries (older than lock timeout)
        long expirationThreshold = System.currentTimeMillis() - config.getDefaultLockTimeoutMs() * 2;
        cleanupExpiredQueueEntries(expirationThreshold);

        logger.debug("Added token {} to queue {} with timestamp {} on {}/{} nodes", token, queueKey, timestamp,
                successfulNodes, redisDrivers.size());
    }

    /**
     * Removes a token from the queue. Uses Redis sorted sets (ZREM) to remove the token.
     */
    private void removeFromQueue(String token) {
        int successfulNodes = 0;

        for (RedisDriver driver : redisDrivers) {
            try {
                if (driver.zRem(queueKey, token)) {
                    successfulNodes++;
                }
            } catch (Exception e) {
                logger.debug("Failed to remove from queue on {}: {}", driver.getIdentifier(), e.getMessage());
            }
        }

        logger.debug("Removed token {} from queue {} on {}/{} nodes", token, queueKey, successfulNodes,
                redisDrivers.size());
    }

    /**
     * Checks if the given token is at the front of the queue. Uses Redis sorted sets (ZRANGE) to get the first element.
     */
    private boolean isAtFrontOfQueue(String token) {
        int votesForFront = 0;

        for (RedisDriver driver : redisDrivers) {
            try {
                // Get the first element in the sorted set (lowest score/timestamp)
                List<String> firstElements = driver.zRange(queueKey, 0, 0);

                if (!firstElements.isEmpty() && token.equals(firstElements.get(0))) {
                    votesForFront++;
                }
            } catch (Exception e) {
                logger.debug("Failed to check queue position on {}: {}", driver.getIdentifier(), e.getMessage());
            }
        }

        // Require quorum agreement that we're at the front
        boolean atFront = votesForFront >= config.getQuorum();
        logger.debug("Token {} is {} at front of queue (votes: {}/{})", token, atFront ? "" : "NOT", votesForFront,
                redisDrivers.size());

        return atFront;
    }

    /**
     * Cleans up expired entries from the queue. Removes entries with timestamps older than the threshold.
     */
    private void cleanupExpiredQueueEntries(long expirationThreshold) {
        for (RedisDriver driver : redisDrivers) {
            try {
                // Remove all entries with score (timestamp) less than threshold
                long removed = driver.zRemRangeByScore(queueKey, 0, expirationThreshold);
                if (removed > 0) {
                    logger.debug("Cleaned up {} expired queue entries on {}", removed, driver.getIdentifier());
                }
            } catch (Exception e) {
                logger.debug("Failed to cleanup queue on {}: {}", driver.getIdentifier(), e.getMessage());
            }
        }
    }

    private LockResult attemptLock() {
        String lockValue = generateToken();
        long startTime = System.currentTimeMillis();
        int successfulNodes = 0;

        for (RedisDriver driver : redisDrivers) {
            try {
                if (driver.setIfNotExists(lockKey, lockValue, config.getDefaultLockTimeoutMs())) {
                    successfulNodes++;
                }
            } catch (Exception e) {
                logger.debug("Failed to acquire lock on {}: {}", driver.getIdentifier(), e.getMessage());
            }
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        long driftTime = (long) (config.getDefaultLockTimeoutMs() * config.getClockDriftFactor()) + 2;
        long validityTime = config.getDefaultLockTimeoutMs() - elapsedTime - driftTime;

        boolean acquired = successfulNodes >= config.getQuorum() && validityTime > 0;

        if (!acquired) {
            releaseLock(lockValue);
        }

        return new LockResult(acquired, validityTime, lockValue, successfulNodes, redisDrivers.size());
    }

    private void releaseLock(String lockValue) {
        for (RedisDriver driver : redisDrivers) {
            try {
                driver.deleteIfValueMatches(lockKey, lockValue);
            } catch (Exception e) {
                logger.warn("Failed to release lock on {}: {}", driver.getIdentifier(), e.getMessage());
            }
        }
    }

    private String generateToken() {
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
        throw new UnsupportedOperationException("Conditions are not supported by distributed fair locks");
    }

    /**
     * Checks if the current thread holds this lock.
     */
    public boolean isHeldByCurrentThread() {
        LockState state = lockState.get();
        return state != null && state.isValid();
    }

    /**
     * Gets the remaining validity time of the lock.
     */
    public long getRemainingValidityTime() {
        LockState state = lockState.get();
        if (state == null || !state.isValid()) {
            return 0;
        }
        return (state.acquisitionTime + state.validityTime) - System.currentTimeMillis();
    }

    /**
     * Gets the hold count for this lock.
     */
    public int getHoldCount() {
        LockState state = lockState.get();
        return state != null ? state.holdCount : 0;
    }
}
