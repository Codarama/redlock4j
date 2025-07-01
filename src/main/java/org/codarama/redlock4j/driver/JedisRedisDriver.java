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

import org.codarama.redlock4j.RedisNodeConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;

/**
 * Jedis implementation of the RedisDriver interface.
 */
public class JedisRedisDriver implements RedisDriver {
    private static final Logger logger = LoggerFactory.getLogger(JedisRedisDriver.class);
    
    private static final String DELETE_IF_VALUE_MATCHES_SCRIPT = 
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "    return redis.call('del', KEYS[1]) " +
        "else " +
        "    return 0 " +
        "end";
    
    private final JedisPool jedisPool;
    private final String identifier;
    
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
}
