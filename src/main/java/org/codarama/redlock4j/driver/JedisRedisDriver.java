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

import org.codarama.redlock4j.configuration.RedisNodeConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Jedis implementation of the RedisDriver interface with automatic CAS/CAD detection.
 *
 * <p>This driver automatically detects and uses the best available method for each operation:
 * <ul>
 *   <li>Native Redis 8.4+ CAS/CAD commands (DELEX, SET IFEQ) when available</li>
 *   <li>Lua script-based operations for older Redis versions</li>
 * </ul>
 * Detection happens once at driver initialization.</p>
 */
public class JedisRedisDriver implements RedisDriver {
    private static final Logger logger = LoggerFactory.getLogger(JedisRedisDriver.class);

    private static final String DELETE_IF_VALUE_MATCHES_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "    return redis.call('del', KEYS[1]) " +
        "else " +
        "    return 0 " +
        "end";

    private static final String SET_IF_VALUE_MATCHES_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "    return redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3]) " +
        "else " +
        "    return nil " +
        "end";

    /**
     * Strategy for CAS/CAD operations.
     */
    private enum CADStrategy {
        /** Use native Redis 8.4+ commands (DELEX, SET IFEQ) */
        NATIVE,
        /** Use Lua scripts for compatibility */
        SCRIPT
    }

    private final JedisPool jedisPool;
    private final String identifier;
    private final CADStrategy cadStrategy;
    
    public JedisRedisDriver(RedisNodeConfiguration config) {
        this.identifier = "redis://" + config.getHost() + ":" + config.getPort();

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10);
        poolConfig.setMaxIdle(5);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);

        // Build Redis URI
        StringBuilder uriBuilder = new StringBuilder("redis://");
        if (config.getPassword() != null && !config.getPassword().trim().isEmpty()) {
            uriBuilder.append(":").append(config.getPassword()).append("@");
        }
        uriBuilder.append(config.getHost()).append(":").append(config.getPort());
        if (config.getDatabase() != 0) {
            uriBuilder.append("/").append(config.getDatabase());
        }

        try {
            java.net.URI redisUri = java.net.URI.create(uriBuilder.toString());
            this.jedisPool = new JedisPool(
                poolConfig,
                redisUri,
                config.getConnectionTimeoutMs(),
                config.getSocketTimeoutMs()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Jedis pool for " + identifier, e);
        }

        logger.debug("Created Jedis driver for {}", identifier);

        // Detect CAS/CAD support once at initialization
        this.cadStrategy = detectCADStrategy();
        logger.info("Using {} strategy for CAS/CAD operations on {}", cadStrategy, identifier);
    }

    /**
     * Detects whether native CAS/CAD commands are available.
     * This is called once during driver initialization.
     */
    private CADStrategy detectCADStrategy() {
        try (Jedis jedis = jedisPool.getResource()) {
            // Try to execute DELEX on a test key
            String testKey = "__redlock4j_cad_test__" + System.currentTimeMillis();
            jedis.sendCommand(
                redis.clients.jedis.Protocol.Command.DELEX,
                testKey, "IFEQ", "test_value"
            );
            logger.debug("Native CAS/CAD commands detected for {}", identifier);
            return CADStrategy.NATIVE;
        } catch (Exception e) {
            logger.debug("Native CAS/CAD commands not available for {}, using Lua scripts: {}",
                identifier, e.getMessage());
            return CADStrategy.SCRIPT;
        }
    }
    
    @Override
    public boolean setIfNotExists(String key, String value, long expireTimeMs) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            SetParams params = SetParams.setParams().nx().px(expireTimeMs);
            String result = jedis.set(key, value, params);
            return "OK".equals(result);
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute SET NX PX command on " + identifier, e);
        }
    }
    
    @Override
    public boolean deleteIfValueMatches(String key, String expectedValue) throws RedisDriverException {
        switch (cadStrategy) {
            case NATIVE:
                return deleteIfValueMatchesNative(key, expectedValue);
            case SCRIPT:
                return deleteIfValueMatchesScript(key, expectedValue);
            default:
                throw new IllegalStateException("Unknown CAD strategy: " + cadStrategy);
        }
    }

    /**
     * Deletes a key using native DELEX command (Redis 8.4+).
     */
    private boolean deleteIfValueMatchesNative(String key, String expectedValue) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.sendCommand(
                redis.clients.jedis.Protocol.Command.DELEX,
                key, "IFEQ", expectedValue
            );
            return Long.valueOf(1).equals(result);
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute DELEX command on " + identifier, e);
        }
    }

    /**
     * Deletes a key using Lua script (legacy compatibility).
     */
    private boolean deleteIfValueMatchesScript(String key, String expectedValue) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(
                DELETE_IF_VALUE_MATCHES_SCRIPT,
                Collections.singletonList(key),
                Collections.singletonList(expectedValue)
            );
            return Long.valueOf(1).equals(result);
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute delete script on " + identifier, e);
        }
    }
    
    @Override
    public boolean isConnected() {
        try (Jedis jedis = jedisPool.getResource()) {
            return "PONG".equals(jedis.ping());
        } catch (Exception e) {
            logger.debug("Connection check failed for {}: {}", identifier, e.getMessage());
            return false;
        }
    }
    
    @Override
    public String getIdentifier() {
        return identifier;
    }
    
    @Override
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            logger.debug("Closed Jedis driver for {}", identifier);
        }
    }

    @Override
    public boolean setIfValueMatches(String key, String newValue, String expectedCurrentValue,
                                    long expireTimeMs) throws RedisDriverException {
        switch (cadStrategy) {
            case NATIVE:
                return setIfValueMatchesNative(key, newValue, expectedCurrentValue, expireTimeMs);
            case SCRIPT:
                return setIfValueMatchesScript(key, newValue, expectedCurrentValue, expireTimeMs);
            default:
                throw new IllegalStateException("Unknown CAD strategy: " + cadStrategy);
        }
    }

    /**
     * Sets a key using native SET IFEQ command (Redis 8.4+).
     */
    private boolean setIfValueMatchesNative(String key, String newValue, String expectedCurrentValue,
                                           long expireTimeMs) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.sendCommand(
                redis.clients.jedis.Protocol.Command.SET,
                key, newValue, "IFEQ", expectedCurrentValue, "PX", String.valueOf(expireTimeMs)
            );
            return "OK".equals(result);
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute SET IFEQ command on " + identifier, e);
        }
    }

    /**
     * Sets a key using Lua script (legacy compatibility).
     */
    private boolean setIfValueMatchesScript(String key, String newValue, String expectedCurrentValue,
                                           long expireTimeMs) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(
                SET_IF_VALUE_MATCHES_SCRIPT,
                Collections.singletonList(key),
                java.util.Arrays.asList(expectedCurrentValue, newValue, String.valueOf(expireTimeMs))
            );
            return "OK".equals(result);
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute SET script on " + identifier, e);
        }
    }

    // ========== Sorted Set Operations ==========

    @Override
    public boolean zAdd(String key, double score, String member) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            Long result = jedis.zadd(key, score, member);
            return result != null && result > 0;
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute ZADD on " + identifier, e);
        }
    }

    @Override
    public boolean zRem(String key, String member) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            Long result = jedis.zrem(key, member);
            return result != null && result > 0;
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute ZREM on " + identifier, e);
        }
    }

    @Override
    public List<String> zRange(String key, long start, long stop) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> result = jedis.zrange(key, start, stop);
            return result != null ? result : Collections.emptyList();
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute ZRANGE on " + identifier, e);
        }
    }

    @Override
    public Double zScore(String key, String member) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.zscore(key, member);
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute ZSCORE on " + identifier, e);
        }
    }

    @Override
    public long zRemRangeByScore(String key, double minScore, double maxScore) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            Long result = jedis.zremrangeByScore(key, minScore, maxScore);
            return result != null ? result : 0;
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute ZREMRANGEBYSCORE on " + identifier, e);
        }
    }

    // ========== String/Counter Operations ==========

    @Override
    public long incr(String key) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            Long result = jedis.incr(key);
            return result != null ? result : 0;
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute INCR on " + identifier, e);
        }
    }

    @Override
    public long decr(String key) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            Long result = jedis.decr(key);
            return result != null ? result : 0;
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute DECR on " + identifier, e);
        }
    }

    @Override
    public String get(String key) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(key);
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute GET on " + identifier, e);
        }
    }

    @Override
    public void setex(String key, String value, long expireTimeMs) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.psetex(key, expireTimeMs, value);
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute SETEX on " + identifier, e);
        }
    }

    @Override
    public long del(String... keys) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            Long result = jedis.del(keys);
            return result != null ? result : 0;
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute DEL on " + identifier, e);
        }
    }

    // ========== Pub/Sub Operations ==========

    @Override
    public long publish(String channel, String message) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            Long result = jedis.publish(channel, message);
            return result != null ? result : 0;
        } catch (JedisException e) {
            throw new RedisDriverException("Failed to execute PUBLISH on " + identifier, e);
        }
    }

    @Override
    public void subscribe(MessageHandler handler, String... channels) throws RedisDriverException {
        try (Jedis jedis = jedisPool.getResource()) {
            JedisPubSub jedisPubSub = new JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    handler.onMessage(channel, message);
                }

                @Override
                public void onSubscribe(String channel, int subscribedChannels) {
                    logger.debug("Subscribed to channel {} on {}", channel, identifier);
                }

                @Override
                public void onUnsubscribe(String channel, int subscribedChannels) {
                    logger.debug("Unsubscribed from channel {} on {}", channel, identifier);
                }
            };
            jedis.subscribe(jedisPubSub, channels);
        } catch (JedisException e) {
            handler.onError(e);
            throw new RedisDriverException("Failed to execute SUBSCRIBE on " + identifier, e);
        }
    }
}
