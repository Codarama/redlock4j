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
import java.time.Duration;

/**
 * RxJava reactive distributed lock interface providing reactive streams for lock operations.
 * This interface uses RxJava 3 reactive types for maximum compatibility with reactive applications.
 */
public interface RxRedlock {
    
    /**
     * Attempts to acquire the lock reactively without waiting.
     * 
     * @return a Single that emits true if the lock was acquired, false otherwise
     */
    Single<Boolean> tryLockRx();
    
    /**
     * Attempts to acquire the lock reactively with a timeout.
     *
     * @param timeout the maximum time to wait for the lock
     * @return a Single that emits true if the lock was acquired within the timeout, false otherwise
     */
    Single<Boolean> tryLockRx(Duration timeout);
    
    /**
     * Acquires the lock reactively, waiting if necessary until the lock becomes available
     * or the acquisition timeout is reached.
     * 
     * @return a Completable that completes when the lock is acquired
     */
    Completable lockRx();
    
    /**
     * Releases the lock reactively.
     * 
     * @return a Completable that completes when the lock is released
     */
    Completable unlockRx();
    
    /**
     * Creates a reactive stream that periodically emits the lock validity time.
     * Useful for monitoring lock health in reactive applications.
     *
     * @param checkInterval the interval between validity checks
     * @return an Observable that emits the remaining validity time at each check
     */
    Observable<Long> validityObservable(Duration checkInterval);
    
    /**
     * Creates a reactive stream that emits lock acquisition attempts with retry logic.
     * This provides fine-grained control over retry behavior in reactive applications.
     *
     * @param maxRetries maximum number of retry attempts
     * @param retryDelay delay between retry attempts
     * @return a Single that emits true when lock is acquired, or error if all retries fail
     */
    Single<Boolean> tryLockWithRetryRx(int maxRetries, Duration retryDelay);
    
    /**
     * Creates an observable that emits lock state changes.
     * Useful for monitoring when locks are acquired or released.
     * 
     * @return an Observable that emits LockState events
     */
    Observable<LockState> lockStateObservable();
    
    /**
     * Checks if the current thread holds this lock.
     * This is a synchronous operation as it only checks local state.
     * 
     * @return true if the current thread holds the lock and it's still valid
     */
    boolean isHeldByCurrentThread();
    
    /**
     * Gets the remaining validity time of the lock for the current thread.
     * This is a synchronous operation as it only checks local state.
     * 
     * @return remaining validity time in milliseconds, or 0 if not held or expired
     */
    long getRemainingValidityTime();
    
    /**
     * Gets the lock key.
     * 
     * @return the lock key
     */
    String getLockKey();
    
    /**
     * Represents the state of a lock for reactive monitoring.
     */
    enum LockState {
        ACQUIRING,
        ACQUIRED,
        RELEASED,
        EXPIRED,
        FAILED
    }
}
