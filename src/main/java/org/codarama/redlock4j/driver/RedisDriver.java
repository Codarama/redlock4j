package org.codarama.redlock4j.driver;

/**
 * Abstraction over different Redis client libraries (Jedis, Lettuce).
 * Provides the minimal interface needed for implementing Redlock.
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
     * Executes a Lua script that deletes a key only if its value matches the expected value.
     * This is used for safe lock release.
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
}
