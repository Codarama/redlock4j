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

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.codarama.redlock4j.RedisNodeConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Lettuce implementation of the RedisDriver interface.
 */
public class LettuceRedisDriver implements RedisDriver {
    private static final Logger logger = LoggerFactory.getLogger(LettuceRedisDriver.class);
    
    private static final String DELETE_IF_VALUE_MATCHES_SCRIPT = 
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "    return redis.call('del', KEYS[1]) " +
        "else " +
        "    return 0 " +
        "end";
    
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String identifier;
    
    public LettuceRedisDriver(RedisNodeConfiguration config) {
        this.identifier = "redis://" + config.getHost() + ":" + config.getPort();
        
        RedisURI.Builder uriBuilder = RedisURI.builder()
            .withHost(config.getHost())
            .withPort(config.getPort())
            .withDatabase(config.getDatabase())
            .withTimeout(Duration.ofMillis(config.getConnectionTimeoutMs()));
        
        if (config.getPassword() != null && !config.getPassword().trim().isEmpty()) {
            uriBuilder.withPassword(config.getPassword().toCharArray());
        }
        
        RedisURI redisURI = uriBuilder.build();
        
        this.redisClient = RedisClient.create(redisURI);
        this.connection = redisClient.connect();
        this.commands = connection.sync();
        
        // Set socket timeout
        connection.setTimeout(Duration.ofMillis(config.getSocketTimeoutMs()));
        
        logger.debug("Created Lettuce driver for {}", identifier);
    }
    
    @Override
    public boolean setIfNotExists(String key, String value, long expireTimeMs) throws RedisDriverException {
        try {
            SetArgs args = SetArgs.Builder.nx().px(expireTimeMs);
            String result = commands.set(key, value, args);
            return "OK".equals(result);
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute SET NX PX command on " + identifier, e);
        }
    }
    
    @Override
    public boolean deleteIfValueMatches(String key, String expectedValue) throws RedisDriverException {
        try {
            Object result = commands.eval(
                DELETE_IF_VALUE_MATCHES_SCRIPT,
                io.lettuce.core.ScriptOutputType.INTEGER,
                new String[]{key},
                expectedValue
            );
            return Long.valueOf(1).equals(result);
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute delete script on " + identifier, e);
        }
    }
    
    @Override
    public boolean isConnected() {
        try {
            return "PONG".equals(commands.ping());
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
        try {
            if (connection != null) {
                connection.close();
            }
            if (redisClient != null) {
                redisClient.shutdown();
            }
            logger.debug("Closed Lettuce driver for {}", identifier);
        } catch (Exception e) {
            logger.warn("Error closing Lettuce driver for {}: {}", identifier, e.getMessage());
        }
    }
}
