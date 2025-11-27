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

import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.codarama.redlock4j.driver.RedisDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A distributed semaphore implementation that limits the number of concurrent accesses
 * to a shared resource. Unlike a lock which allows only one holder, a semaphore allows
 * a configurable number of permits.
 * 
 * <p><b>Key Features:</b></p>
 * <ul>
 *   <li>Configurable number of permits</li>
 *   <li>Multiple threads can acquire permits simultaneously</li>
 *   <li>Blocks when no permits are available</li>
 *   <li>Automatic permit release on timeout</li>
 * </ul>
 * 
 * <p><b>Use Cases:</b></p>
 * <ul>
 *   <li>Rate limiting: Limit concurrent API calls</li>
 *   <li>Resource pooling: Limit concurrent database connections</li>
 *   <li>Throttling: Control concurrent access to expensive operations</li>
 * </ul>
 * 
 * <p><b>Example Usage:</b></p>
 * <pre>{@code
 * // Create a semaphore with 5 permits
 * RedlockSemaphore semaphore = new RedlockSemaphore("api-limiter", 5, redisDrivers, config);
 * 
 * // Acquire a permit
 * if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
 *     try {
 *         // Perform rate-limited operation
 *         callExternalAPI();
 *     } finally {
 *         semaphore.release();
 *     }
 * }
 * }</pre>
 */
public class RedlockSemaphore {
    private static final Logger logger = LoggerFactory.getLogger(RedlockSemaphore.class);
    
    private final String semaphoreKey;
    private final int maxPermits;
    private final List<RedisDriver> redisDrivers;
    private final RedlockConfiguration config;
    private final SecureRandom secureRandom;
    
    // Thread-local storage for permit state
    private final ThreadLocal<PermitState> permitState = new ThreadLocal<>();
    
    private static class PermitState {
        final List<String> permitIds; // IDs of acquired permits
        final long acquisitionTime;
        final long validityTime;

        PermitState(List<String> permitIds, long acquisitionTime, long validityTime) {
            this.permitIds = new ArrayList<>(permitIds);
            this.acquisitionTime = acquisitionTime;
            this.validityTime = validityTime;
        }

        boolean isValid() {
            return System.currentTimeMillis() < acquisitionTime + validityTime;
        }
    }
    
    /**
     * Creates a new distributed semaphore.
     * 
     * @param semaphoreKey the key for this semaphore
     * @param maxPermits the maximum number of permits available
     * @param redisDrivers the Redis drivers to use
     * @param config the Redlock configuration
     */
    public RedlockSemaphore(String semaphoreKey, int maxPermits, List<RedisDriver> redisDrivers, 
                           RedlockConfiguration config) {
        if (maxPermits <= 0) {
            throw new IllegalArgumentException("Max permits must be positive");
        }
        
        this.semaphoreKey = semaphoreKey;
        this.maxPermits = maxPermits;
        this.redisDrivers = redisDrivers;
        this.config = config;
        this.secureRandom = new SecureRandom();
        
        logger.debug("Created RedlockSemaphore {} with {} permits", semaphoreKey, maxPermits);
    }
    
    /**
     * Acquires a permit, blocking until one is available.
     * 
     * @throws RedlockException if unable to acquire within the configured timeout
     */
    public void acquire() throws InterruptedException {
        if (!tryAcquire(config.getLockAcquisitionTimeoutMs(), TimeUnit.MILLISECONDS)) {
            throw new RedlockException("Failed to acquire semaphore permit within timeout: " + semaphoreKey);
        }
    }
    
    /**
     * Acquires the specified number of permits, blocking until they are available.
     * 
     * @param permits the number of permits to acquire
     * @throws RedlockException if unable to acquire within the configured timeout
     */
    public void acquire(int permits) throws InterruptedException {
        if (!tryAcquire(permits, config.getLockAcquisitionTimeoutMs(), TimeUnit.MILLISECONDS)) {
            throw new RedlockException("Failed to acquire " + permits + " semaphore permits within timeout: " 
                + semaphoreKey);
        }
    }
    
    /**
     * Acquires a permit if one is immediately available.
     * 
     * @return true if a permit was acquired, false otherwise
     */
    public boolean tryAcquire() {
        try {
            return tryAcquire(1, 0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * Acquires a permit, waiting up to the specified time if necessary.
     * 
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout
     * @return true if a permit was acquired, false if the timeout elapsed
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return tryAcquire(1, timeout, unit);
    }
    
    /**
     * Acquires the specified number of permits, waiting up to the specified time if necessary.
     * 
     * @param permits the number of permits to acquire
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout
     * @return true if the permits were acquired, false if the timeout elapsed
     */
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        if (permits <= 0 || permits > maxPermits) {
            throw new IllegalArgumentException("Invalid number of permits: " + permits);
        }
        
        // Check if current thread already has permits
        PermitState currentState = permitState.get();
        if (currentState != null && currentState.isValid()) {
            logger.warn("Thread already holds permits, release before acquiring more");
            return false;
        }

        long timeoutMs = unit.toMillis(timeout);
        long startTime = System.currentTimeMillis();

        for (int attempt = 0; attempt <= config.getMaxRetryAttempts(); attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }

            SemaphoreResult result = attemptAcquire(permits);
            if (result.isAcquired()) {
                permitState.set(new PermitState(result.getPermitIds(), System.currentTimeMillis(), 
                    result.getValidityTimeMs()));
                logger.debug("Successfully acquired {} permit(s) for {} on attempt {}", 
                    permits, semaphoreKey, attempt + 1);
                return true;
            }

            // Check if we've exceeded the timeout
            if (timeoutMs > 0 && (System.currentTimeMillis() - startTime) >= timeoutMs) {
                logger.debug("Semaphore acquisition timeout exceeded for {}", semaphoreKey);
                break;
            }

            // Wait before retrying
            if (attempt < config.getMaxRetryAttempts()) {
                Thread.sleep(config.getRetryDelayMs());
            }
        }

        return false;
    }
    
    /**
     * Releases a permit, returning it to the semaphore.
     */
    public void release() {
        release(1);
    }
    
    /**
     * Releases the specified number of permits.
     * 
     * @param permits the number of permits to release
     */
    public void release(int permits) {
        PermitState state = permitState.get();
        if (state == null) {
            logger.warn("Attempting to release semaphore permits but no permit state found");
            return;
        }

        if (permits > state.permitIds.size()) {
            logger.warn("Attempting to release more permits than held");
            permits = state.permitIds.size();
        }

        // Release the specified number of permits
        List<String> toRelease = state.permitIds.subList(0, permits);
        releasePermits(toRelease);
        
        // Update or clear state
        if (permits >= state.permitIds.size()) {
            permitState.remove();
        } else {
            state.permitIds.subList(0, permits).clear();
        }
        
        logger.debug("Successfully released {} permit(s) for {}", permits, semaphoreKey);
    }
    
    /**
     * Returns the number of permits currently available (approximate).
     * Note: This is an estimate and may not be accurate in a distributed environment.
     */
    public int availablePermits() {
        // Note: This would require counting active permits across all nodes
        // For now, return a placeholder
        return maxPermits;
    }
    
    /**
     * Attempts to acquire the specified number of permits.
     */
    private SemaphoreResult attemptAcquire(int permits) {
        List<String> permitIds = new ArrayList<>();
        long startTime = System.currentTimeMillis();
        
        // Try to acquire permits by creating unique keys
        for (int i = 0; i < permits; i++) {
            String permitId = generatePermitId();
            String permitKey = semaphoreKey + ":permit:" + permitId;
            
            int successfulNodes = 0;
            for (RedisDriver driver : redisDrivers) {
                try {
                    if (driver.setIfNotExists(permitKey, permitId, config.getDefaultLockTimeoutMs())) {
                        successfulNodes++;
                    }
                } catch (Exception e) {
                    logger.debug("Failed to acquire permit on {}: {}", driver.getIdentifier(), e.getMessage());
                }
            }
            
            if (successfulNodes >= config.getQuorum()) {
                permitIds.add(permitId);
            } else {
                // Failed to acquire this permit, rollback
                releasePermits(permitIds);
                return new SemaphoreResult(false, 0, new ArrayList<>());
            }
        }
        
        long elapsedTime = System.currentTimeMillis() - startTime;
        long driftTime = (long) (config.getDefaultLockTimeoutMs() * config.getClockDriftFactor()) + 2;
        long validityTime = config.getDefaultLockTimeoutMs() - elapsedTime - driftTime;
        
        boolean acquired = permitIds.size() == permits && validityTime > 0;
        
        if (!acquired) {
            releasePermits(permitIds);
            return new SemaphoreResult(false, 0, new ArrayList<>());
        }
        
        return new SemaphoreResult(true, validityTime, permitIds);
    }
    
    /**
     * Releases the specified permits.
     */
    private void releasePermits(List<String> permitIds) {
        for (String permitId : permitIds) {
            String permitKey = semaphoreKey + ":permit:" + permitId;
            for (RedisDriver driver : redisDrivers) {
                try {
                    driver.deleteIfValueMatches(permitKey, permitId);
                } catch (Exception e) {
                    logger.warn("Failed to release permit {} on {}: {}", 
                        permitId, driver.getIdentifier(), e.getMessage());
                }
            }
        }
    }
    
    private String generatePermitId() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * Result of a semaphore acquisition attempt.
     */
    private static class SemaphoreResult {
        private final boolean acquired;
        private final long validityTimeMs;
        private final List<String> permitIds;

        SemaphoreResult(boolean acquired, long validityTimeMs, List<String> permitIds) {
            this.acquired = acquired;
            this.validityTimeMs = validityTimeMs;
            this.permitIds = permitIds;
        }

        boolean isAcquired() {
            return acquired;
        }

        long getValidityTimeMs() {
            return validityTimeMs;
        }

        List<String> getPermitIds() {
            return permitIds;
        }
    }
}

