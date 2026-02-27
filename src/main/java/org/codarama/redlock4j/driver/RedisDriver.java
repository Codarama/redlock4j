/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.driver;

import java.util.List;
import java.util.Set;

/**
 * Abstraction over different Redis client libraries (Jedis, Lettuce). Provides the minimal interface needed for
 * implementing Redlock and advanced locking primitives.
 *
 * <p>
 * This interface automatically uses the best available implementation for each operation:
 * <ul>
 * <li>Native Redis 8.4+ CAS/CAD commands when available (DELEX, SET IFEQ)</li>
 * <li>Lua script-based operations for older Redis versions</li>
 * </ul>
 * The detection and selection happens automatically at driver initialization.
 * </p>
 */
public interface RedisDriver extends AutoCloseable {

    /**
     * Attempts to set a key with a value if the key doesn't exist, with an expiration time. This corresponds to the
     * Redis SET command with NX and PX options.
     *
     * @param key
     *            the key to set
     * @param value
     *            the value to set
     * @param expireTimeMs
     *            expiration time in milliseconds
     * @return true if the key was set, false if it already existed
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    boolean setIfNotExists(String key, String value, long expireTimeMs) throws RedisDriverException;

    /**
     * Deletes a key only if its value matches the expected value. This is used for safe lock release.
     *
     * <p>
     * This method automatically uses the best available implementation:
     * <ul>
     * <li><strong>Redis 8.4+:</strong> Native DELEX command for optimal performance</li>
     * <li><strong>Older versions:</strong> Lua script for compatibility</li>
     * </ul>
     * The implementation is selected automatically at driver initialization based on Redis server capabilities.
     * </p>
     *
     * @param key
     *            the key to potentially delete
     * @param expectedValue
     *            the expected value of the key
     * @return true if the key was deleted, false if it didn't exist or had a different value
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    boolean deleteIfValueMatches(String key, String expectedValue) throws RedisDriverException;

    /**
     * Checks if the driver is connected and ready to use.
     *
     * @return true if connected, false otherwise
     */
    boolean isConnected();

    /**
     * Gets a human-readable identifier for this Redis instance.
     *
     * @return identifier string (e.g., "redis://localhost:6379")
     */
    String getIdentifier();

    /**
     * Closes the connection to Redis.
     */
    @Override
    void close();

    /**
     * Sets a key only if its current value matches the expected value. This is used for atomic compare-and-swap
     * operations like lock extension.
     *
     * <p>
     * This method automatically uses the best available implementation:
     * <ul>
     * <li><strong>Redis 8.4+:</strong> Native SET IFEQ command for optimal performance</li>
     * <li><strong>Older versions:</strong> Lua script for compatibility</li>
     * </ul>
     * The implementation is selected automatically at driver initialization based on Redis server capabilities.
     * </p>
     *
     * @param key
     *            the key to set
     * @param newValue
     *            the new value to set
     * @param expectedCurrentValue
     *            the expected current value that must match
     * @param expireTimeMs
     *            expiration time in milliseconds
     * @return true if the key was set, false if the current value didn't match
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    boolean setIfValueMatches(String key, String newValue, String expectedCurrentValue, long expireTimeMs)
            throws RedisDriverException;

    // ========== Sorted Set Operations (for Fair Lock) ==========

    /**
     * Adds a member to a sorted set with the given score. If the member already exists, its score is updated.
     *
     * @param key
     *            the sorted set key
     * @param score
     *            the score for the member
     * @param member
     *            the member to add
     * @return true if a new member was added, false if an existing member's score was updated
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    boolean zAdd(String key, double score, String member) throws RedisDriverException;

    /**
     * Removes a member from a sorted set.
     *
     * @param key
     *            the sorted set key
     * @param member
     *            the member to remove
     * @return true if the member was removed, false if it didn't exist
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    boolean zRem(String key, String member) throws RedisDriverException;

    /**
     * Returns a range of members from a sorted set, ordered by score (ascending).
     *
     * @param key
     *            the sorted set key
     * @param start
     *            the start index (0-based, inclusive)
     * @param stop
     *            the stop index (0-based, inclusive, -1 for end)
     * @return list of members in the specified range
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    List<String> zRange(String key, long start, long stop) throws RedisDriverException;

    /**
     * Returns the score of a member in a sorted set.
     *
     * @param key
     *            the sorted set key
     * @param member
     *            the member to get the score for
     * @return the score, or null if the member doesn't exist
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    Double zScore(String key, String member) throws RedisDriverException;

    /**
     * Removes all members from a sorted set with scores less than or equal to the given score.
     *
     * @param key
     *            the sorted set key
     * @param maxScore
     *            the maximum score (inclusive)
     * @return the number of members removed
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    long zRemRangeByScore(String key, double minScore, double maxScore) throws RedisDriverException;

    // ========== String/Counter Operations (for ReadWriteLock, CountDownLatch) ==========

    /**
     * Increments the integer value of a key by one. If the key doesn't exist, it is set to 0 before performing the
     * operation.
     *
     * @param key
     *            the key to increment
     * @return the value after incrementing
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    long incr(String key) throws RedisDriverException;

    /**
     * Decrements the integer value of a key by one. If the key doesn't exist, it is set to 0 before performing the
     * operation.
     *
     * @param key
     *            the key to decrement
     * @return the value after decrementing
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    long decr(String key) throws RedisDriverException;

    /**
     * Atomically decrements the value of a key and publishes a message to a channel if the new value is zero or less.
     * This combines DECR and conditional PUBLISH into a single atomic operation.
     *
     * @param key
     *            the key to decrement
     * @param channel
     *            the channel to publish to if count reaches zero
     * @param message
     *            the message to publish
     * @return the value after decrementing
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    long decrAndPublishIfZero(String key, String channel, String message) throws RedisDriverException;

    /**
     * Gets the value of a key.
     *
     * @param key
     *            the key to get
     * @return the value, or null if the key doesn't exist
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    String get(String key) throws RedisDriverException;

    /**
     * Sets a key to a value with an expiration time.
     *
     * @param key
     *            the key to set
     * @param value
     *            the value to set
     * @param expireTimeMs
     *            expiration time in milliseconds
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    void setex(String key, String value, long expireTimeMs) throws RedisDriverException;

    /**
     * Deletes one or more keys.
     *
     * @param keys
     *            the keys to delete
     * @return the number of keys that were deleted
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    long del(String... keys) throws RedisDriverException;

    // ========== Pub/Sub Operations (for CountDownLatch notifications) ==========

    /**
     * Publishes a message to a channel.
     *
     * @param channel
     *            the channel to publish to
     * @param message
     *            the message to publish
     * @return the number of clients that received the message
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    long publish(String channel, String message) throws RedisDriverException;

    /**
     * Subscribes to one or more channels and processes messages with the given handler. This is a blocking operation
     * that should typically be run in a separate thread.
     *
     * @param handler
     *            the message handler
     * @param channels
     *            the channels to subscribe to
     * @throws RedisDriverException
     *             if there's an error communicating with Redis
     */
    void subscribe(MessageHandler handler, String... channels) throws RedisDriverException;

    /**
     * Handler interface for processing pub/sub messages.
     */
    interface MessageHandler {
        /**
         * Called when a message is received on a subscribed channel.
         *
         * @param channel
         *            the channel the message was received on
         * @param message
         *            the message content
         */
        void onMessage(String channel, String message);

        /**
         * Called when an error occurs during subscription.
         *
         * @param error
         *            the error that occurred
         */
        void onError(Throwable error);
    }
}
