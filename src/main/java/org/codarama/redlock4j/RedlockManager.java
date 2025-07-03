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
package org.codarama.redlock4j;

import org.codarama.redlock4j.driver.JedisRedisDriver;
import org.codarama.redlock4j.driver.LettuceRedisDriver;
import org.codarama.redlock4j.driver.RedisDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.Lock;

/**
 * Factory for creating Redlock instances. Manages the lifecycle of Redis connections.
 */
public class RedlockManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RedlockManager.class);
    
    public enum DriverType {
        JEDIS, LETTUCE
    }
    
    private final RedlockConfiguration config;
    private final List<RedisDriver> redisDrivers;
    private final DriverType driverType;
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutorService;
    private volatile boolean closed = false;
    
    /**
     * Creates a RedlockManager with Jedis drivers.
     * 
     * @param config the Redlock configuration
     * @return a new RedlockManager instance
     */
    public static RedlockManager withJedis(RedlockConfiguration config) {
        return new RedlockManager(config, DriverType.JEDIS);
    }
    
    /**
     * Creates a RedlockManager with Lettuce drivers.
     * 
     * @param config the Redlock configuration
     * @return a new RedlockManager instance
     */
    public static RedlockManager withLettuce(RedlockConfiguration config) {
        return new RedlockManager(config, DriverType.LETTUCE);
    }
    
    private RedlockManager(RedlockConfiguration config, DriverType driverType) {
        this.config = config;
        this.driverType = driverType;
        this.redisDrivers = createDrivers();
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "redlock-async-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        this.scheduledExecutorService = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "redlock-scheduled-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });

        logger.info("Created RedlockManager with {} driver and {} Redis nodes",
                   driverType, redisDrivers.size());
    }
    
    private List<RedisDriver> createDrivers() {
        List<RedisDriver> drivers = new ArrayList<>();
        
        for (RedisNodeConfiguration nodeConfig : config.getRedisNodes()) {
            try {
                RedisDriver driver;
                switch (driverType) {
                    case JEDIS:
                        driver = new JedisRedisDriver(nodeConfig);
                        break;
                    case LETTUCE:
                        driver = new LettuceRedisDriver(nodeConfig);
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported driver type: " + driverType);
                }
                
                // Test the connection
                if (!driver.isConnected()) {
                    logger.warn("Failed to connect to Redis node: {}", driver.getIdentifier());
                    driver.close();
                    continue;
                }
                
                drivers.add(driver);
                logger.debug("Successfully connected to Redis node: {}", driver.getIdentifier());
                
            } catch (Exception e) {
                logger.error("Failed to create driver for Redis node {}:{}", 
                           nodeConfig.getHost(), nodeConfig.getPort(), e);
            }
        }
        
        if (drivers.isEmpty()) {
            throw new RedlockException("Failed to connect to any Redis nodes");
        }
        
        if (drivers.size() < config.getQuorum()) {
            logger.warn("Connected to {} Redis nodes, but quorum requires {}. " +
                       "Lock operations may fail.", drivers.size(), config.getQuorum());
        }
        
        return drivers;
    }
    
    /**
     * Creates a new distributed lock for the given key.
     *
     * @param lockKey the key to lock
     * @return a new Lock instance
     * @throws RedlockException if the manager is closed
     */
    public Lock createLock(String lockKey) {
        if (closed) {
            throw new RedlockException("RedlockManager is closed");
        }

        if (lockKey == null || lockKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Lock key cannot be null or empty");
        }

        return new Redlock(lockKey, redisDrivers, config);
    }

    /**
     * Creates a new asynchronous distributed lock for the given key.
     *
     * @param lockKey the key to lock
     * @return a new AsyncRedlock instance
     * @throws RedlockException if the manager is closed
     */
    public AsyncRedlock createAsyncLock(String lockKey) {
        if (closed) {
            throw new RedlockException("RedlockManager is closed");
        }

        if (lockKey == null || lockKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Lock key cannot be null or empty");
        }

        return new AsyncRxRedlock(lockKey, redisDrivers, config, executorService, scheduledExecutorService);
    }

    /**
     * Creates a new RxJava reactive distributed lock for the given key.
     *
     * @param lockKey the key to lock
     * @return a new RxRedlock instance
     * @throws RedlockException if the manager is closed
     */
    public RxRedlock createRxLock(String lockKey) {
        if (closed) {
            throw new RedlockException("RedlockManager is closed");
        }

        if (lockKey == null || lockKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Lock key cannot be null or empty");
        }

        return new AsyncRxRedlock(lockKey, redisDrivers, config, executorService, scheduledExecutorService);
    }

    /**
     * Creates a comprehensive lock that implements both async and reactive interfaces.
     * This lock supports both CompletionStage and RxJava reactive types.
     *
     * @param lockKey the key to lock
     * @return a new lock instance implementing both AsyncRedlock and RxRedlock
     * @throws RedlockException if the manager is closed
     */
    public AsyncRxRedlock createAsyncRxLock(String lockKey) {
        if (closed) {
            throw new RedlockException("RedlockManager is closed");
        }

        if (lockKey == null || lockKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Lock key cannot be null or empty");
        }

        return new AsyncRxRedlock(lockKey, redisDrivers, config, executorService, scheduledExecutorService);
    }
    
    /**
     * Gets the number of connected Redis nodes.
     * 
     * @return the number of connected nodes
     */
    public int getConnectedNodeCount() {
        if (closed) {
            return 0;
        }
        
        int connected = 0;
        for (RedisDriver driver : redisDrivers) {
            if (driver.isConnected()) {
                connected++;
            }
        }
        return connected;
    }
    
    /**
     * Gets the required quorum size.
     * 
     * @return the quorum size
     */
    public int getQuorum() {
        return config.getQuorum();
    }
    
    /**
     * Checks if the manager has enough connected nodes to potentially acquire locks.
     * 
     * @return true if connected nodes >= quorum
     */
    public boolean isHealthy() {
        return !closed && getConnectedNodeCount() >= getQuorum();
    }
    
    /**
     * Gets the driver type being used.
     * 
     * @return the driver type
     */
    public DriverType getDriverType() {
        return driverType;
    }
    
    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;

        // Shutdown executor services
        try {
            executorService.shutdown();
            if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            scheduledExecutorService.shutdown();
            if (!scheduledExecutorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                scheduledExecutorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduledExecutorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close Redis drivers
        for (RedisDriver driver : redisDrivers) {
            try {
                driver.close();
            } catch (Exception e) {
                logger.warn("Error closing Redis driver {}: {}", driver.getIdentifier(), e.getMessage());
            }
        }

        logger.info("Closed RedlockManager");
    }
}
