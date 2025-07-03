/*
 * MIT License
 *
 * Copyright (c) 2025 Codarama
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.codarama.redlock4j;

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
        
        LockState(String lockValue, long acquisitionTime, long validityTime) {
            this.lockValue = lockValue;
            this.acquisitionTime = acquisitionTime;
            this.validityTime = validityTime;
        }
        
        boolean isValid() {
            return System.currentTimeMillis() < acquisitionTime + validityTime;
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
        long timeoutMs = unit.toMillis(time);
        long startTime = System.currentTimeMillis();
        
        for (int attempt = 0; attempt <= config.getMaxRetryAttempts(); attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            
            LockResult result = attemptLock();
            if (result.isAcquired()) {
                lockState.set(new LockState(result.getLockValue(), System.currentTimeMillis(), result.getValidityTimeMs()));
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
}
