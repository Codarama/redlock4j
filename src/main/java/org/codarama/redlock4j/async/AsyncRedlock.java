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

import org.codarama.redlock4j.RedlockException;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * Asynchronous distributed lock interface using CompletionStage for non-blocking operations.
 * This interface provides async alternatives to the standard Lock interface methods.
 */
public interface AsyncRedlock {
    
    /**
     * Attempts to acquire the lock asynchronously without waiting.
     * 
     * @return a CompletionStage that completes with true if the lock was acquired, false otherwise
     */
    CompletionStage<Boolean> tryLockAsync();
    
    /**
     * Attempts to acquire the lock asynchronously with a timeout.
     *
     * @param timeout the maximum time to wait for the lock
     * @return a CompletionStage that completes with true if the lock was acquired within the timeout, false otherwise
     */
    CompletionStage<Boolean> tryLockAsync(Duration timeout);
    
    /**
     * Acquires the lock asynchronously, waiting if necessary until the lock becomes available
     * or the acquisition timeout is reached.
     * 
     * @return a CompletionStage that completes when the lock is acquired
     * @throws RedlockException if the lock cannot be acquired within the configured timeout
     */
    CompletionStage<Void> lockAsync();
    
    /**
     * Releases the lock asynchronously.
     * 
     * @return a CompletionStage that completes when the lock is released
     */
    CompletionStage<Void> unlockAsync();
    
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
     * Gets the hold count for the async lock.
     * This indicates how many times the lock has been acquired.
     * This is a synchronous operation as it only checks local state.
     *
     * @return hold count, or 0 if not held
     */
    int getHoldCount();
}
