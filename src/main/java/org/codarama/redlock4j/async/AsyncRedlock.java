/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.async;

import org.codarama.redlock4j.RedlockException;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * Asynchronous distributed lock interface using CompletionStage for non-blocking operations. This interface provides
 * async alternatives to the standard Lock interface methods.
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
     * @param timeout
     *            the maximum time to wait for the lock
     * @return a CompletionStage that completes with true if the lock was acquired within the timeout, false otherwise
     */
    CompletionStage<Boolean> tryLockAsync(Duration timeout);

    /**
     * Acquires the lock asynchronously, waiting if necessary until the lock becomes available or the acquisition
     * timeout is reached.
     * 
     * @return a CompletionStage that completes when the lock is acquired
     * @throws RedlockException
     *             if the lock cannot be acquired within the configured timeout
     */
    CompletionStage<Void> lockAsync();

    /**
     * Releases the lock asynchronously.
     * 
     * @return a CompletionStage that completes when the lock is released
     */
    CompletionStage<Void> unlockAsync();

    /**
     * Checks if the current thread holds this lock. This is a synchronous operation as it only checks local state.
     * 
     * @return true if the current thread holds the lock and it's still valid
     */
    boolean isHeldByCurrentThread();

    /**
     * Gets the remaining validity time of the lock for the current thread. This is a synchronous operation as it only
     * checks local state.
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
     * Gets the hold count for the async lock. This indicates how many times the lock has been acquired. This is a
     * synchronous operation as it only checks local state.
     *
     * @return hold count, or 0 if not held
     */
    int getHoldCount();

    /**
     * Extends the validity time of the lock asynchronously.
     * <p>
     * This method attempts to extend the lock on a quorum of Redis nodes using the same lock value. The extension is
     * only successful if:
     * <ul>
     * <li>The lock is currently held and valid</li>
     * <li>The extension succeeds on at least a quorum (N/2+1) of nodes</li>
     * <li>The new validity time (after accounting for clock drift) is positive</li>
     * </ul>
     * <p>
     * <b>Important limitations:</b>
     * <ul>
     * <li>Lock extension is for efficiency, not correctness</li>
     * <li>Does not solve the GC pause problem - use fencing tokens for correctness</li>
     * <li>Should not be used as a substitute for proper timeout configuration</li>
     * </ul>
     *
     * @param additionalTime
     *            additional time to extend the lock
     * @return a CompletionStage that completes with true if the lock was successfully extended, false otherwise
     * @throws IllegalArgumentException
     *             if additionalTime is negative or zero
     */
    CompletionStage<Boolean> extendAsync(Duration additionalTime);
}
