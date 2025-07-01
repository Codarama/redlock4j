package org.codarama.redlock4j;

import org.codarama.redlock4j.driver.JedisRedisDriver;
import org.codarama.redlock4j.driver.LettuceRedisDriver;
import org.codarama.redlock4j.driver.RedisDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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
        
        return new RedlockLock(lockKey, redisDrivers, config);
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
