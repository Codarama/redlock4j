/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.driver;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import org.codarama.redlock4j.configuration.RedisNodeConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Lettuce implementation of the RedisDriver interface with automatic CAS/CAD detection.
 *
 * <p>
 * This driver automatically detects and uses the best available method for each operation:
 * <ul>
 * <li>Native Redis 8.4+ CAS/CAD commands (DELEX, SET IFEQ) when available</li>
 * <li>Lua script-based operations for older Redis versions</li>
 * </ul>
 * Detection happens once at driver initialization.
 * </p>
 */
public class LettuceRedisDriver implements RedisDriver {
    private static final Logger logger = LoggerFactory.getLogger(LettuceRedisDriver.class);

    private static final String DELETE_IF_VALUE_MATCHES_SCRIPT = "if redis.call('get', KEYS[1]) == ARGV[1] then "
            + "    return redis.call('del', KEYS[1]) " + "else " + "    return 0 " + "end";

    private static final String SET_IF_VALUE_MATCHES_SCRIPT = "if redis.call('get', KEYS[1]) == ARGV[1] then "
            + "    return redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3]) " + "else " + "    return nil " + "end";

    /**
     * Strategy for CAS/CAD operations.
     */
    private enum CADStrategy {
        /** Use native Redis 8.4+ commands (DELEX, SET IFEQ) */
        NATIVE,
        /** Use Lua scripts for compatibility */
        SCRIPT
    }

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String identifier;
    private final CADStrategy cadStrategy;

    public LettuceRedisDriver(RedisNodeConfiguration config) {
        this(config, null, null, null);
    }

    // Package-private constructor for testing with dependency injection
    LettuceRedisDriver(RedisNodeConfiguration config, RedisClient redisClient,
            StatefulRedisConnection<String, String> connection, RedisCommands<String, String> commands) {
        this.identifier = "redis://" + config.getHost() + ":" + config.getPort();

        if (redisClient != null && connection != null && commands != null) {
            // Use injected dependencies (for testing)
            this.redisClient = redisClient;
            this.connection = connection;
            this.commands = commands;
            logger.debug("Created Lettuce driver for {} with injected dependencies", identifier);
        } else {
            // Create real connections (production)
            RedisURI.Builder uriBuilder = RedisURI.builder().withHost(config.getHost()).withPort(config.getPort())
                    .withDatabase(config.getDatabase()).withTimeout(Duration.ofMillis(config.getConnectionTimeoutMs()));

            if (config.getPassword() != null && !config.getPassword().trim().isEmpty()) {
                uriBuilder.withPassword(config.getPassword().toCharArray());
            }

            RedisURI redisURI = uriBuilder.build();

            this.redisClient = RedisClient.create(redisURI);
            this.connection = this.redisClient.connect();
            this.commands = this.connection.sync();

            // Set socket timeout
            this.connection.setTimeout(Duration.ofMillis(config.getSocketTimeoutMs()));

            logger.debug("Created Lettuce driver for {}", identifier);
        }

        // Detect CAS/CAD support once at initialization
        this.cadStrategy = detectCADStrategy();
        logger.info("Using {} strategy for CAS/CAD operations on {}", cadStrategy, identifier);
    }

    /**
     * Detects whether native CAS/CAD commands are available. This is called once during driver initialization.
     */
    private CADStrategy detectCADStrategy() {
        try {
            // Try to execute DELEX on a test key
            String testKey = "__redlock4j_cad_test__" + System.currentTimeMillis();
            commands.dispatch(io.lettuce.core.protocol.CommandType.DELEX,
                    new io.lettuce.core.output.IntegerOutput<>(io.lettuce.core.codec.StringCodec.UTF8),
                    new io.lettuce.core.protocol.CommandArgs<>(io.lettuce.core.codec.StringCodec.UTF8).addKey(testKey)
                            .add("IFEQ").add("test_value"));
            logger.debug("Native CAS/CAD commands detected for {}", identifier);
            return CADStrategy.NATIVE;
        } catch (Exception e) {
            logger.debug("Native CAS/CAD commands not available for {}, using Lua scripts: {}", identifier,
                    e.getMessage());
            return CADStrategy.SCRIPT;
        }
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
        switch (cadStrategy) {
            case NATIVE :
                return deleteIfValueMatchesNative(key, expectedValue);
            case SCRIPT :
                return deleteIfValueMatchesScript(key, expectedValue);
            default :
                throw new IllegalStateException("Unknown CAD strategy: " + cadStrategy);
        }
    }

    /**
     * Deletes a key using native DELEX command (Redis 8.4+).
     */
    private boolean deleteIfValueMatchesNative(String key, String expectedValue) throws RedisDriverException {
        try {
            Object result = commands.dispatch(io.lettuce.core.protocol.CommandType.DELEX,
                    new io.lettuce.core.output.IntegerOutput<>(io.lettuce.core.codec.StringCodec.UTF8),
                    new io.lettuce.core.protocol.CommandArgs<>(io.lettuce.core.codec.StringCodec.UTF8).addKey(key)
                            .add("IFEQ").add(expectedValue));
            return Long.valueOf(1).equals(result);
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute DELEX command on " + identifier, e);
        }
    }

    /**
     * Deletes a key using Lua script (legacy compatibility).
     */
    private boolean deleteIfValueMatchesScript(String key, String expectedValue) throws RedisDriverException {
        try {
            Object result = commands.eval(DELETE_IF_VALUE_MATCHES_SCRIPT, io.lettuce.core.ScriptOutputType.INTEGER,
                    new String[]{key}, expectedValue);
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

    @Override
    public boolean setIfValueMatches(String key, String newValue, String expectedCurrentValue, long expireTimeMs)
            throws RedisDriverException {
        switch (cadStrategy) {
            case NATIVE :
                return setIfValueMatchesNative(key, newValue, expectedCurrentValue, expireTimeMs);
            case SCRIPT :
                return setIfValueMatchesScript(key, newValue, expectedCurrentValue, expireTimeMs);
            default :
                throw new IllegalStateException("Unknown CAD strategy: " + cadStrategy);
        }
    }

    /**
     * Sets a key using native SET IFEQ command (Redis 8.4+).
     */
    private boolean setIfValueMatchesNative(String key, String newValue, String expectedCurrentValue, long expireTimeMs)
            throws RedisDriverException {
        try {
            String result = commands.dispatch(io.lettuce.core.protocol.CommandType.SET,
                    new io.lettuce.core.output.StatusOutput<>(io.lettuce.core.codec.StringCodec.UTF8),
                    new io.lettuce.core.protocol.CommandArgs<>(io.lettuce.core.codec.StringCodec.UTF8).addKey(key)
                            .addValue(newValue).add("IFEQ").add(expectedCurrentValue).add("PX").add(expireTimeMs));
            return "OK".equals(result);
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute SET IFEQ command on " + identifier, e);
        }
    }

    /**
     * Sets a key using Lua script (legacy compatibility).
     */
    private boolean setIfValueMatchesScript(String key, String newValue, String expectedCurrentValue, long expireTimeMs)
            throws RedisDriverException {
        try {
            Object result = commands.eval(SET_IF_VALUE_MATCHES_SCRIPT, io.lettuce.core.ScriptOutputType.STATUS,
                    new String[]{key}, expectedCurrentValue, newValue, String.valueOf(expireTimeMs));
            return "OK".equals(result);
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute SET script on " + identifier, e);
        }
    }

    // ========== Sorted Set Operations ==========

    @Override
    public boolean zAdd(String key, double score, String member) throws RedisDriverException {
        try {
            Long result = commands.zadd(key, score, member);
            return result != null && result > 0;
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute ZADD on " + identifier, e);
        }
    }

    @Override
    public boolean zRem(String key, String member) throws RedisDriverException {
        try {
            Long result = commands.zrem(key, member);
            return result != null && result > 0;
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute ZREM on " + identifier, e);
        }
    }

    @Override
    public List<String> zRange(String key, long start, long stop) throws RedisDriverException {
        try {
            List<String> result = commands.zrange(key, start, stop);
            return result != null ? result : java.util.Collections.emptyList();
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute ZRANGE on " + identifier, e);
        }
    }

    @Override
    public Double zScore(String key, String member) throws RedisDriverException {
        try {
            return commands.zscore(key, member);
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute ZSCORE on " + identifier, e);
        }
    }

    @Override
    public long zRemRangeByScore(String key, double minScore, double maxScore) throws RedisDriverException {
        try {
            Long result = commands.zremrangebyscore(key, minScore, maxScore);
            return result != null ? result : 0;
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute ZREMRANGEBYSCORE on " + identifier, e);
        }
    }

    // ========== String/Counter Operations ==========

    @Override
    public long incr(String key) throws RedisDriverException {
        try {
            Long result = commands.incr(key);
            return result != null ? result : 0;
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute INCR on " + identifier, e);
        }
    }

    @Override
    public long decr(String key) throws RedisDriverException {
        try {
            Long result = commands.decr(key);
            return result != null ? result : 0;
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute DECR on " + identifier, e);
        }
    }

    @Override
    public String get(String key) throws RedisDriverException {
        try {
            return commands.get(key);
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute GET on " + identifier, e);
        }
    }

    @Override
    public void setex(String key, String value, long expireTimeMs) throws RedisDriverException {
        try {
            commands.psetex(key, expireTimeMs, value);
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute SETEX on " + identifier, e);
        }
    }

    @Override
    public long del(String... keys) throws RedisDriverException {
        try {
            Long result = commands.del(keys);
            return result != null ? result : 0;
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute DEL on " + identifier, e);
        }
    }

    // ========== Pub/Sub Operations ==========

    @Override
    public long publish(String channel, String message) throws RedisDriverException {
        try {
            Long result = commands.publish(channel, message);
            return result != null ? result : 0;
        } catch (Exception e) {
            throw new RedisDriverException("Failed to execute PUBLISH on " + identifier, e);
        }
    }

    @Override
    public void subscribe(MessageHandler handler, String... channels) throws RedisDriverException {
        try {
            StatefulRedisPubSubConnection<String, String> pubSubConnection = redisClient.connectPubSub();
            RedisPubSubCommands<String, String> pubSubCommands = pubSubConnection.sync();

            pubSubConnection.addListener(new RedisPubSubAdapter<String, String>() {
                @Override
                public void message(String channel, String message) {
                    handler.onMessage(channel, message);
                }

                @Override
                public void subscribed(String channel, long count) {
                    logger.debug("Subscribed to channel {} on {}", channel, identifier);
                }

                @Override
                public void unsubscribed(String channel, long count) {
                    logger.debug("Unsubscribed from channel {} on {}", channel, identifier);
                }
            });

            pubSubCommands.subscribe(channels);
        } catch (Exception e) {
            handler.onError(e);
            throw new RedisDriverException("Failed to execute SUBSCRIBE on " + identifier, e);
        }
    }
}
