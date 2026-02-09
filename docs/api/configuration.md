# Configuration API Reference

Detailed reference for configuring Redlock4j.

## RedlockConfiguration

Main configuration class for Redlock behavior.

### Builder Pattern

```java
RedlockConfiguration config = RedlockConfiguration.builder()
    .retryCount(3)
    .retryDelay(200)
    .clockDriftFactor(0.01)
    .build();
```

### Configuration Properties

#### retryCount

Number of times to retry lock acquisition if it fails.

**Type:** `int`  
**Default:** `3`  
**Range:** `0` to `Integer.MAX_VALUE`

```java
RedlockConfiguration config = RedlockConfiguration.builder()
    .retryCount(5)  // Retry up to 5 times
    .build();
```

**Use Cases:**
- High contention: Increase retry count
- Low contention: Decrease for faster failure
- Time-sensitive: Set to 0 for no retries

#### retryDelay

Delay in milliseconds between retry attempts.

**Type:** `int`  
**Default:** `200`  
**Range:** `0` to `Integer.MAX_VALUE`

```java
RedlockConfiguration config = RedlockConfiguration.builder()
    .retryDelay(100)  // Wait 100ms between retries
    .build();
```

**Use Cases:**
- High contention: Increase delay to reduce Redis load
- Low latency required: Decrease delay
- Exponential backoff: Implement custom retry logic

#### clockDriftFactor

Factor to compensate for clock drift between Redis instances.

**Type:** `double`  
**Default:** `0.01` (1%)  
**Range:** `0.0` to `1.0`

```java
RedlockConfiguration config = RedlockConfiguration.builder()
    .clockDriftFactor(0.02)  // 2% clock drift
    .build();
```

**Formula:**
```
validity_time = ttl - elapsed_time - (ttl * clockDriftFactor)
```

**Use Cases:**
- Synchronized clocks: Use smaller factor (0.001)
- Unsynchronized clocks: Use larger factor (0.05)
- Default: 0.01 is safe for most cases

## Complete Configuration Example

```java
import org.codarama.redlock4j.Redlock;
import org.codarama.redlock4j.RedlockConfiguration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class ConfigurationExample {
    public static Redlock createRedlock() {
        // Configure Jedis pools
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(128);
        poolConfig.setMaxIdle(64);
        poolConfig.setMinIdle(16);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        
        JedisPool pool1 = new JedisPool(poolConfig, "redis1.example.com", 6379);
        JedisPool pool2 = new JedisPool(poolConfig, "redis2.example.com", 6379);
        JedisPool pool3 = new JedisPool(poolConfig, "redis3.example.com", 6379);
        
        // Configure Redlock
        RedlockConfiguration config = RedlockConfiguration.builder()
            .retryCount(5)
            .retryDelay(200)
            .clockDriftFactor(0.01)
            .build();
        
        return new Redlock(config, pool1, pool2, pool3);
    }
}
```

## Environment-Specific Configurations

### Development

```java
// Minimal configuration for development
RedlockConfiguration devConfig = RedlockConfiguration.builder()
    .retryCount(1)
    .retryDelay(100)
    .build();

// Single Redis instance is acceptable for dev
JedisPool devPool = new JedisPool("localhost", 6379);
Redlock devRedlock = new Redlock(devConfig, devPool);
```

### Staging

```java
// Moderate configuration for staging
RedlockConfiguration stagingConfig = RedlockConfiguration.builder()
    .retryCount(3)
    .retryDelay(200)
    .clockDriftFactor(0.01)
    .build();

// 3 Redis instances
Redlock stagingRedlock = new Redlock(
    stagingConfig,
    new JedisPool("redis1.staging", 6379),
    new JedisPool("redis2.staging", 6379),
    new JedisPool("redis3.staging", 6379)
);
```

### Production

```java
// Robust configuration for production
RedlockConfiguration prodConfig = RedlockConfiguration.builder()
    .retryCount(5)
    .retryDelay(200)
    .clockDriftFactor(0.01)
    .build();

// 5 Redis instances for high availability
Redlock prodRedlock = new Redlock(
    prodConfig,
    new JedisPool(poolConfig, "redis1.prod", 6379),
    new JedisPool(poolConfig, "redis2.prod", 6379),
    new JedisPool(poolConfig, "redis3.prod", 6379),
    new JedisPool(poolConfig, "redis4.prod", 6379),
    new JedisPool(poolConfig, "redis5.prod", 6379)
);
```

## Redis Connection Configuration

### Jedis Pool Configuration

Configure Jedis connection pools for optimal performance:

```java
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

JedisPoolConfig poolConfig = new JedisPoolConfig();
poolConfig.setMaxTotal(128);
poolConfig.setMaxIdle(128);
poolConfig.setMinIdle(16);
poolConfig.setTestOnBorrow(true);
poolConfig.setTestOnReturn(true);
poolConfig.setTestWhileIdle(true);

JedisPool pool = new JedisPool(poolConfig, "localhost", 6379);
```

### Lettuce Client Configuration

Configure Lettuce clients:

```java
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;

RedisURI redisUri = RedisURI.builder()
    .withHost("localhost")
    .withPort(6379)
    .withTimeout(Duration.ofSeconds(5))
    .build();

ClientOptions options = ClientOptions.builder()
    .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(5)))
    .build();

RedisClient client = RedisClient.create(redisUri);
client.setOptions(options);
```

## Configuration Best Practices

### Lock TTL

Choose an appropriate TTL based on your use case:

- **Short operations** (< 1 second): Use 5-10 second TTL
- **Medium operations** (1-10 seconds): Use 30-60 second TTL
- **Long operations** (> 10 seconds): Consider if distributed locking is the right solution

!!! warning "TTL Too Short"
    If your TTL is too short, the lock might expire before your operation completes, allowing another client to acquire the lock.

### Retry Strategy

- **High contention**: Increase retry count and delay
- **Low contention**: Reduce retry count for faster failure
- **Time-sensitive**: Reduce retry count to fail fast

```java
// High contention scenario
RedlockConfiguration highContention = RedlockConfiguration.builder()
    .retryCount(10)      // More retries
    .retryDelay(500)     // Longer delay
    .build();

// Low latency requirement
RedlockConfiguration lowLatency = RedlockConfiguration.builder()
    .retryCount(1)       // Fail fast
    .retryDelay(50)      // Short delay
    .build();
```

### Number of Redis Instances

- **Minimum**: 3 instances for fault tolerance
- **Recommended**: 5 instances for high availability
- **Odd number**: Always use an odd number to ensure clear majority

### Clock Drift

```java
// Well-synchronized clocks (NTP)
RedlockConfiguration syncedClocks = RedlockConfiguration.builder()
    .clockDriftFactor(0.001)  // 0.1% drift
    .build();

// Unsynchronized clocks
RedlockConfiguration unsyncedClocks = RedlockConfiguration.builder()
    .clockDriftFactor(0.05)   // 5% drift
    .build();
```

## Next Steps

- [Core API](core.md) - Core API reference
- [Basic Usage](../guide/basic-usage.md) - Learn common usage patterns

