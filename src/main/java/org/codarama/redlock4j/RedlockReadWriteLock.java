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
import java.util.concurrent.locks.ReadWriteLock;

/**
 * A distributed read-write lock implementation that allows multiple concurrent readers or a single exclusive writer.
 * This is useful for scenarios where reads are frequent and writes are infrequent.
 * 
 * <p>
 * <b>Key Features:</b>
 * </p>
 * <ul>
 * <li>Multiple readers can hold the lock simultaneously</li>
 * <li>Writers have exclusive access (no readers or other writers)</li>
 * <li>Readers are blocked while a writer holds the lock</li>
 * <li>Writers are blocked while any readers or writers hold the lock</li>
 * </ul>
 * 
 * <p>
 * <b>Implementation Details:</b>
 * </p>
 * <ul>
 * <li>Uses Redis counters to track the number of active readers</li>
 * <li>Uses a separate key for the write lock</li>
 * <li>Readers increment/decrement the reader count atomically</li>
 * <li>Writers must wait for reader count to reach zero</li>
 * </ul>
 * 
 * <p>
 * <b>Example Usage:</b>
 * </p>
 * 
 * <pre>
 * {
 *     &#64;code
 *     RedlockReadWriteLock rwLock = new RedlockReadWriteLock("resource", redisDrivers, config);
 * 
 *     // Reading
 *     rwLock.readLock().lock();
 *     try {
 *         // Multiple threads can read simultaneously
 *         readData();
 *     } finally {
 *         rwLock.readLock().unlock();
 *     }
 * 
 *     // Writing
 *     rwLock.writeLock().lock();
 *     try {
 *         // Exclusive access for writing
 *         writeData();
 *     } finally {
 *         rwLock.writeLock().unlock();
 *     }
 * }
 * </pre>
 */
public class RedlockReadWriteLock implements ReadWriteLock {
    private static final Logger logger = LoggerFactory.getLogger(RedlockReadWriteLock.class);

    private final String resourceKey;
    private final ReadLock readLock;
    private final WriteLock writeLock;

    public RedlockReadWriteLock(String resourceKey, List<RedisDriver> redisDrivers, RedlockConfiguration config) {
        this.resourceKey = resourceKey;
        this.readLock = new ReadLock(resourceKey, redisDrivers, config);
        this.writeLock = new WriteLock(resourceKey, redisDrivers, config);
    }

    @Override
    public Lock readLock() {
        return readLock;
    }

    @Override
    public Lock writeLock() {
        return writeLock;
    }

    /**
     * Read lock implementation that allows multiple concurrent readers.
     */
    private static class ReadLock implements Lock {
        private static final Logger logger = LoggerFactory.getLogger(ReadLock.class);

        private final String readCountKey;
        private final String writeLockKey;
        private final List<RedisDriver> redisDrivers;
        private final RedlockConfiguration config;
        private final SecureRandom secureRandom;

        private final ThreadLocal<LockState> lockState = new ThreadLocal<>();

        private static class LockState {
            final String lockValue;
            final long acquisitionTime;
            final long validityTime;
            int holdCount;

            LockState(String lockValue, long acquisitionTime, long validityTime) {
                this.lockValue = lockValue;
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

        ReadLock(String resourceKey, List<RedisDriver> redisDrivers, RedlockConfiguration config) {
            this.readCountKey = resourceKey + ":readers";
            this.writeLockKey = resourceKey + ":write";
            this.redisDrivers = redisDrivers;
            this.config = config;
            this.secureRandom = new SecureRandom();
        }

        @Override
        public void lock() {
            try {
                if (!tryLock(config.getLockAcquisitionTimeoutMs(), TimeUnit.MILLISECONDS)) {
                    throw new RedlockException("Failed to acquire read lock within timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RedlockException("Interrupted while acquiring read lock", e);
            }
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            if (!tryLock(config.getLockAcquisitionTimeoutMs(), TimeUnit.MILLISECONDS)) {
                throw new RedlockException("Failed to acquire read lock within timeout");
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
                logger.debug("Reentrant read lock acquisition (hold count: {})", currentState.holdCount);
                return true;
            }

            long timeoutMs = unit.toMillis(time);
            long startTime = System.currentTimeMillis();

            for (int attempt = 0; attempt <= config.getMaxRetryAttempts(); attempt++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }

                // Check if there's an active writer
                if (!isWriteLockHeld()) {
                    // Try to increment reader count
                    String lockValue = generateLockValue();
                    if (incrementReaderCount(lockValue)) {
                        lockState.set(
                                new LockState(lockValue, System.currentTimeMillis(), config.getDefaultLockTimeoutMs()));
                        logger.debug("Successfully acquired read lock on attempt {}", attempt + 1);
                        return true;
                    }
                }

                // Check if we've exceeded the timeout
                if (timeoutMs > 0 && (System.currentTimeMillis() - startTime) >= timeoutMs) {
                    logger.debug("Read lock acquisition timeout exceeded");
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
                logger.warn("Attempting to unlock read lock but no lock state found");
                return;
            }

            // Handle reentrancy
            int remainingHolds = state.decrementHoldCount();
            if (remainingHolds > 0) {
                logger.debug("Reentrant unlock for read lock (remaining holds: {})", remainingHolds);
                return;
            }

            // Final unlock - decrement reader count
            decrementReaderCount(state.lockValue);
            lockState.remove();
            logger.debug("Successfully released read lock");
        }

        private boolean isWriteLockHeld() {
            // Check if write lock exists on a quorum of nodes using GET
            int nodesWithoutWriteLock = 0;
            for (RedisDriver driver : redisDrivers) {
                try {
                    String value = driver.get(writeLockKey);
                    if (value == null) {
                        nodesWithoutWriteLock++;
                    }
                } catch (Exception e) {
                    logger.debug("Failed to check write lock on {}: {}", driver.getIdentifier(), e.getMessage());
                }
            }
            // If a quorum of nodes don't have the write lock, it's not held
            return nodesWithoutWriteLock < config.getQuorum();
        }

        private boolean incrementReaderCount(String lockValue) {
            // Use Redis INCR to atomically increment the reader count
            int successfulNodes = 0;

            for (RedisDriver driver : redisDrivers) {
                try {
                    // Increment the reader count atomically
                    long count = driver.incr(readCountKey);

                    // Set expiration on the counter key to prevent leaks
                    if (count == 1) {
                        // First reader, set expiration
                        driver.setex(readCountKey, String.valueOf(count), config.getDefaultLockTimeoutMs() * 2);
                    }

                    // Store the lock value for this reader
                    driver.setex(readCountKey + ":" + lockValue, "1", config.getDefaultLockTimeoutMs());

                    successfulNodes++;
                    logger.debug("Incremented reader count to {} on {}", count, driver.getIdentifier());
                } catch (Exception e) {
                    logger.debug("Failed to increment reader count on {}: {}", driver.getIdentifier(), e.getMessage());
                }
            }

            return successfulNodes >= config.getQuorum();
        }

        private void decrementReaderCount(String lockValue) {
            // Use Redis DECR to atomically decrement the reader count
            for (RedisDriver driver : redisDrivers) {
                try {
                    // Decrement the reader count atomically
                    long count = driver.decr(readCountKey);

                    // Delete the lock value for this reader
                    driver.del(readCountKey + ":" + lockValue);

                    // If count reaches 0, clean up the counter key
                    if (count <= 0) {
                        driver.del(readCountKey);
                    }

                    logger.debug("Decremented reader count to {} on {}", count, driver.getIdentifier());
                } catch (Exception e) {
                    logger.warn("Failed to decrement reader count on {}: {}", driver.getIdentifier(), e.getMessage());
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
            throw new UnsupportedOperationException("Conditions are not supported");
        }
    }

    /**
     * Write lock implementation that provides exclusive access. Writers must wait for all readers to finish before
     * acquiring the lock.
     */
    private static class WriteLock implements Lock {
        private static final Logger logger = LoggerFactory.getLogger(WriteLock.class);

        private final Redlock underlyingLock;
        private final String readCountKey;
        private final List<RedisDriver> redisDrivers;
        private final RedlockConfiguration config;

        WriteLock(String resourceKey, List<RedisDriver> redisDrivers, RedlockConfiguration config) {
            this.underlyingLock = new Redlock(resourceKey + ":write", redisDrivers, config);
            this.readCountKey = resourceKey + ":readers";
            this.redisDrivers = redisDrivers;
            this.config = config;
        }

        @Override
        public void lock() {
            // Wait for readers to finish before acquiring write lock
            waitForReadersToFinish();
            underlyingLock.lock();
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            // Wait for readers to finish before acquiring write lock
            waitForReadersToFinish();
            underlyingLock.lockInterruptibly();
        }

        @Override
        public boolean tryLock() {
            // Check if there are active readers
            if (hasActiveReaders()) {
                return false;
            }
            return underlyingLock.tryLock();
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            long timeoutMs = unit.toMillis(time);
            long startTime = System.currentTimeMillis();

            // Wait for readers to finish with timeout
            while (hasActiveReaders()) {
                if (timeoutMs > 0 && (System.currentTimeMillis() - startTime) >= timeoutMs) {
                    logger.debug("Timeout waiting for readers to finish");
                    return false;
                }
                Thread.sleep(config.getRetryDelayMs());
            }

            // Try to acquire write lock with remaining time
            long remainingTime = timeoutMs - (System.currentTimeMillis() - startTime);
            if (remainingTime <= 0) {
                return underlyingLock.tryLock();
            }
            return underlyingLock.tryLock(remainingTime, TimeUnit.MILLISECONDS);
        }

        @Override
        public void unlock() {
            underlyingLock.unlock();
        }

        @Override
        public Condition newCondition() {
            throw new UnsupportedOperationException("Conditions are not supported");
        }

        /**
         * Waits for all active readers to finish.
         */
        private void waitForReadersToFinish() {
            while (hasActiveReaders()) {
                try {
                    Thread.sleep(config.getRetryDelayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RedlockException("Interrupted while waiting for readers", e);
                }
            }
        }

        /**
         * Checks if there are active readers using atomic GET operation.
         */
        private boolean hasActiveReaders() {
            int nodesWithoutReaders = 0;

            for (RedisDriver driver : redisDrivers) {
                try {
                    String countStr = driver.get(readCountKey);
                    if (countStr == null || Long.parseLong(countStr) <= 0) {
                        nodesWithoutReaders++;
                    }
                } catch (Exception e) {
                    logger.debug("Failed to check reader count on {}: {}", driver.getIdentifier(), e.getMessage());
                }
            }

            // If a quorum of nodes have no readers, we can proceed
            return nodesWithoutReaders < config.getQuorum();
        }
    }
}
