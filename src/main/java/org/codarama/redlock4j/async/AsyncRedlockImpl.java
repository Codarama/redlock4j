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
package org.codarama.redlock4j.async;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import org.codarama.redlock4j.LockResult;
import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.codarama.redlock4j.RedlockException;
import org.codarama.redlock4j.driver.RedisDriver;
import org.codarama.redlock4j.driver.RedisDriverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/**
 * Implementation supporting both AsyncRedlock and AsyncRedlockImpl interfaces.
 * Provides asynchronous CompletionStage and RxJava reactive capabilities.
 */
public class AsyncRedlockImpl implements AsyncRedlock, RxRedlock {
    private static final Logger logger = LoggerFactory.getLogger(AsyncRedlockImpl.class);
    
    private final String lockKey;
    private final List<RedisDriver> redisDrivers;
    private final RedlockConfiguration config;
    private final SecureRandom secureRandom;
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;
    
    // Shared lock state for async operations (not thread-local)
    private volatile LockStateInfo lockState;

    // RxJava subject for lock state changes
    private final BehaviorSubject<LockState> lockStateSubject = BehaviorSubject.createDefault(LockState.RELEASED);
    
    private static class LockStateInfo {
        final String lockValue;
        final long acquisitionTime;
        final long validityTime;
        volatile int holdCount; // For reentrancy - volatile for thread safety

        LockStateInfo(String lockValue, long acquisitionTime, long validityTime) {
            this.lockValue = lockValue;
            this.acquisitionTime = acquisitionTime;
            this.validityTime = validityTime;
            this.holdCount = 1; // Initial acquisition
        }

        boolean isValid() {
            return System.currentTimeMillis() < acquisitionTime + validityTime;
        }

        synchronized void incrementHoldCount() {
            holdCount++;
        }

        synchronized int decrementHoldCount() {
            return --holdCount;
        }
    }
    
    public AsyncRedlockImpl(String lockKey, List<RedisDriver> redisDrivers,
                             RedlockConfiguration config, ExecutorService executorService,
                             ScheduledExecutorService scheduledExecutorService) {
        this.lockKey = lockKey;
        this.redisDrivers = redisDrivers;
        this.config = config;
        this.secureRandom = new SecureRandom();
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
    }
    
    // AsyncRedlock implementation (CompletionStage)
    
    @Override
    public CompletionStage<Boolean> tryLockAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Check if already held (reentrancy)
                LockStateInfo currentState = lockState;
                if (currentState != null && currentState.isValid()) {
                    currentState.incrementHoldCount();
                    logger.debug("Reentrant async lock acquisition for {} (hold count: {})", lockKey, currentState.holdCount);
                    return true;
                }

                lockStateSubject.onNext(LockState.ACQUIRING);
                LockResult result = attemptLock();
                if (result.isAcquired()) {
                    lockState = new LockStateInfo(result.getLockValue(), System.currentTimeMillis(), result.getValidityTimeMs());
                    lockStateSubject.onNext(LockState.ACQUIRED);
                    logger.debug("Successfully acquired async lock {}", lockKey);
                    return true;
                } else {
                    lockStateSubject.onNext(LockState.FAILED);
                    return false;
                }
            } catch (Exception e) {
                lockStateSubject.onNext(LockState.FAILED);
                logger.error("Error in async tryLock for {}: {}", lockKey, e.getMessage());
                throw new CompletionException(new RedlockException("Failed to acquire lock", e));
            }
        }, executorService);
    }
    
    @Override
    public CompletionStage<Boolean> tryLockAsync(Duration timeout) {
        long timeoutMs = timeout.toMillis();
        long startTime = System.currentTimeMillis();

        return tryLockWithRetryAsync(timeoutMs, startTime, 0);
    }
    
    private CompletionStage<Boolean> tryLockWithRetryAsync(long timeoutMs, long startTime, int attempt) {
        return tryLockAsync().thenCompose(acquired -> {
            if (acquired) {
                return CompletableFuture.completedFuture(true);
            }
            
            // Check timeout
            if (timeoutMs > 0 && (System.currentTimeMillis() - startTime) >= timeoutMs) {
                return CompletableFuture.completedFuture(false);
            }
            
            // Check max attempts
            if (attempt >= config.getMaxRetryAttempts()) {
                return CompletableFuture.completedFuture(false);
            }
            
            // Schedule retry with delay
            long delay = config.getRetryDelayMs() + ThreadLocalRandom.current().nextLong(config.getRetryDelayMs());
            
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            scheduledExecutorService.schedule(() -> {
                tryLockWithRetryAsync(timeoutMs, startTime, attempt + 1)
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            future.completeExceptionally(throwable);
                        } else {
                            future.complete(result);
                        }
                    });
            }, delay, TimeUnit.MILLISECONDS);
            
            return future;
        });
    }
    
    @Override
    public CompletionStage<Void> lockAsync() {
        return tryLockAsync(Duration.ofMillis(config.getLockAcquisitionTimeoutMs()))
            .thenCompose(acquired -> {
                if (acquired) {
                    return CompletableFuture.completedFuture(null);
                } else {
                    CompletableFuture<Void> failedFuture = new CompletableFuture<>();
                    failedFuture.completeExceptionally(
                        new RedlockException("Failed to acquire lock within timeout: " + lockKey));
                    return failedFuture;
                }
            });
    }
    
    @Override
    public CompletionStage<Void> unlockAsync() {
        return CompletableFuture.runAsync(() -> {
            LockStateInfo state = lockState;
            if (state == null) {
                logger.warn("Attempting to unlock {} but no lock state found", lockKey);
                return;
            }

            if (!state.isValid()) {
                logger.warn("Lock {} has expired, cannot safely unlock", lockKey);
                lockState = null;
                lockStateSubject.onNext(LockState.EXPIRED);
                return;
            }

            // Handle reentrancy - only release when hold count reaches 0
            int remainingHolds = state.decrementHoldCount();
            if (remainingHolds > 0) {
                logger.debug("Reentrant async unlock for {} (remaining holds: {})", lockKey, remainingHolds);
                return;
            }

            // Final unlock - release the distributed lock
            releaseLock(state.lockValue);
            lockState = null;
            lockStateSubject.onNext(LockState.RELEASED);
            logger.debug("Successfully released async lock {}", lockKey);
        }, executorService);
    }
    
    // AsyncRedlockImpl implementation (RxJava)
    
    @Override
    public Single<Boolean> tryLockRx() {
        return Single.fromCompletionStage(tryLockAsync())
            .subscribeOn(Schedulers.io());
    }
    
    @Override
    public Single<Boolean> tryLockRx(Duration timeout) {
        return Single.fromCompletionStage(tryLockAsync(timeout))
            .subscribeOn(Schedulers.io());
    }
    
    @Override
    public Completable lockRx() {
        return Completable.fromCompletionStage(lockAsync())
            .subscribeOn(Schedulers.io());
    }
    
    @Override
    public Completable unlockRx() {
        return Completable.fromCompletionStage(unlockAsync())
            .subscribeOn(Schedulers.io());
    }
    
    @Override
    public Observable<Long> validityObservable(Duration checkInterval) {
        return Observable.interval(checkInterval.toMillis(), TimeUnit.MILLISECONDS, Schedulers.io())
            .map(tick -> getRemainingValidityTime())
            .takeWhile(validity -> validity > 0);
    }

    @Override
    public Single<Boolean> tryLockWithRetryRx(int maxRetries, Duration retryDelay) {
        return tryLockRx()
            .retry(maxRetries)
            .delay(retryDelay.toMillis(), TimeUnit.MILLISECONDS);
    }
    
    @Override
    public Observable<LockState> lockStateObservable() {
        return lockStateSubject.distinctUntilChanged();
    }

    @Override
    public Single<Boolean> extendRx(Duration additionalTime) {
        return Single.fromCompletionStage(extendAsync(additionalTime))
            .subscribeOn(Schedulers.io());
    }

    // Common methods
    
    @Override
    public boolean isHeldByCurrentThread() {
        LockStateInfo state = lockState;
        return state != null && state.isValid();
    }

    @Override
    public long getRemainingValidityTime() {
        LockStateInfo state = lockState;
        if (state == null) {
            return 0;
        }
        long remaining = state.acquisitionTime + state.validityTime - System.currentTimeMillis();
        return Math.max(0, remaining);
    }
    
    @Override
    public String getLockKey() {
        return lockKey;
    }

    /**
     * Gets the hold count for the async lock.
     * This indicates how many times the lock has been acquired.
     *
     * @return hold count, or 0 if not held
     */
    public int getHoldCount() {
        LockStateInfo state = lockState;
        return state != null && state.isValid() ? state.holdCount : 0;
    }

    @Override
    public CompletionStage<Boolean> extendAsync(Duration additionalTime) {
        if (additionalTime.isNegative() || additionalTime.isZero()) {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("Additional time must be positive"));
            return future;
        }

        return CompletableFuture.supplyAsync(() -> {
            LockStateInfo state = lockState;
            if (state == null || !state.isValid()) {
                logger.debug("Cannot extend lock {} - not held or expired", lockKey);
                return false;
            }

            long startTime = System.currentTimeMillis();
            int successfulNodes = 0;
            long additionalTimeMs = additionalTime.toMillis();
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
                // Update lock state with new validity time
                LockStateInfo newState = new LockStateInfo(state.lockValue, System.currentTimeMillis(), newValidityTime);
                newState.holdCount = state.holdCount; // Preserve hold count
                lockState = newState;
                logger.debug("Successfully extended async lock {} on {}/{} nodes (new validity: {}ms)",
                        lockKey, successfulNodes, redisDrivers.size(), newValidityTime);
            } else {
                logger.debug("Failed to extend async lock {} - only {}/{} nodes succeeded (quorum: {})",
                        lockKey, successfulNodes, redisDrivers.size(), config.getQuorum());
            }

            return extended;
        }, executorService);
    }

    // Private helper methods
    
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
}
