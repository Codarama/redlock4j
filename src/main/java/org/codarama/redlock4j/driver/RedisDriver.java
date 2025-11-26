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
package org.codarama.redlock4j.driver;

/**
 * Abstraction over different Redis client libraries (Jedis, Lettuce).
 * Provides the minimal interface needed for implementing Redlock.
 *
 * <p>This interface automatically uses the best available implementation for each operation:
 * <ul>
 *   <li>Native Redis 8.4+ CAS/CAD commands when available (DELEX, SET IFEQ)</li>
 *   <li>Lua script-based operations for older Redis versions</li>
 * </ul>
 * The detection and selection happens automatically at driver initialization.</p>
 */
public interface RedisDriver extends AutoCloseable {
    
    /**
     * Attempts to set a key with a value if the key doesn't exist, with an expiration time.
     * This corresponds to the Redis SET command with NX and PX options.
     *
     * @param key the key to set
     * @param value the value to set
     * @param expireTimeMs expiration time in milliseconds
     * @return true if the key was set, false if it already existed
     * @throws RedisDriverException if there's an error communicating with Redis
     */
    boolean setIfNotExists(String key, String value, long expireTimeMs) throws RedisDriverException;

    /**
     * Deletes a key only if its value matches the expected value.
     * This is used for safe lock release.
     *
     * <p>This method automatically uses the best available implementation:
     * <ul>
     *   <li><strong>Redis 8.4+:</strong> Native DELEX command for optimal performance</li>
     *   <li><strong>Older versions:</strong> Lua script for compatibility</li>
     * </ul>
     * The implementation is selected automatically at driver initialization based on
     * Redis server capabilities.</p>
     *
     * @param key the key to potentially delete
     * @param expectedValue the expected value of the key
     * @return true if the key was deleted, false if it didn't exist or had a different value
     * @throws RedisDriverException if there's an error communicating with Redis
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
     * Sets a key only if its current value matches the expected value.
     * This is used for atomic compare-and-swap operations like lock extension.
     *
     * <p>This method automatically uses the best available implementation:
     * <ul>
     *   <li><strong>Redis 8.4+:</strong> Native SET IFEQ command for optimal performance</li>
     *   <li><strong>Older versions:</strong> Lua script for compatibility</li>
     * </ul>
     * The implementation is selected automatically at driver initialization based on
     * Redis server capabilities.</p>
     *
     * @param key the key to set
     * @param newValue the new value to set
     * @param expectedCurrentValue the expected current value that must match
     * @param expireTimeMs expiration time in milliseconds
     * @return true if the key was set, false if the current value didn't match
     * @throws RedisDriverException if there's an error communicating with Redis
     */
    boolean setIfValueMatches(String key, String newValue,
                             String expectedCurrentValue,
                             long expireTimeMs)
            throws RedisDriverException;
}
