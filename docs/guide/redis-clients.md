# Redis Clients

Redlock4j supports both Jedis and Lettuce Redis clients through a clean driver abstraction.

## Jedis

Jedis is a synchronous Redis client that's simple and straightforward.

### Setup

```xml
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>7.1.0</version>
</dependency>
```

### Basic Usage

```java
import redis.clients.jedis.JedisPool;
import org.codarama.redlock4j.Redlock;

JedisPool pool1 = new JedisPool("localhost", 6379);
JedisPool pool2 = new JedisPool("localhost", 6380);
JedisPool pool3 = new JedisPool("localhost", 6381);

Redlock redlock = new Redlock(pool1, pool2, pool3);
```

### Connection Pool Configuration

```java
import redis.clients.jedis.JedisPoolConfig;

JedisPoolConfig config = new JedisPoolConfig();
config.setMaxTotal(128);
config.setMaxIdle(128);
config.setMinIdle(16);
config.setTestOnBorrow(true);
config.setTestOnReturn(true);
config.setTestWhileIdle(true);
config.setMinEvictableIdleTimeMillis(60000);
config.setTimeBetweenEvictionRunsMillis(30000);
config.setNumTestsPerEvictionRun(3);
config.setBlockWhenExhausted(true);

JedisPool pool = new JedisPool(config, "localhost", 6379, 2000);
```

### Advantages

- Simple and easy to use
- Synchronous API is straightforward
- Lower memory footprint
- Good for simple use cases

### Disadvantages

- Blocking I/O
- Less efficient for high-throughput scenarios
- No built-in async support

## Lettuce

Lettuce is an advanced Redis client with async and reactive support.

### Setup

```xml
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
    <version>7.1.0.RELEASE</version>
</dependency>
```

### Basic Usage

```java
import io.lettuce.core.RedisClient;
import org.codarama.redlock4j.Redlock;

RedisClient client1 = RedisClient.create("redis://localhost:6379");
RedisClient client2 = RedisClient.create("redis://localhost:6380");
RedisClient client3 = RedisClient.create("redis://localhost:6381");

Redlock redlock = new Redlock(client1, client2, client3);
```

### Advanced Configuration

```java
import io.lettuce.core.RedisURI;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;

// Configure client resources
ClientResources resources = DefaultClientResources.builder()
    .ioThreadPoolSize(4)
    .computationThreadPoolSize(4)
    .build();

// Configure Redis URI
RedisURI redisUri = RedisURI.builder()
    .withHost("localhost")
    .withPort(6379)
    .withTimeout(Duration.ofSeconds(5))
    .withDatabase(0)
    .build();

// Configure client options
ClientOptions options = ClientOptions.builder()
    .autoReconnect(true)
    .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(5)))
    .build();

RedisClient client = RedisClient.create(resources, redisUri);
client.setOptions(options);
```

### Advantages

- Non-blocking I/O with Netty
- Async and reactive API support
- Better performance for high-throughput
- Advanced features (clustering, sentinel)

### Disadvantages

- More complex API
- Higher memory footprint
- Steeper learning curve

## Choosing Between Jedis and Lettuce

### Use Jedis When:

- You need a simple, straightforward API
- Your application is primarily synchronous
- You have moderate throughput requirements
- You want minimal dependencies

### Use Lettuce When:

- You need high throughput
- You want async/reactive programming
- You're using Redis Cluster or Sentinel
- You need advanced features

## Mixed Usage

You can use different clients for different Redis instances:

```java
// Mix Jedis and Lettuce (not recommended, but possible)
JedisPool jedisPool = new JedisPool("localhost", 6379);
RedisClient lettuceClient1 = RedisClient.create("redis://localhost:6380");
RedisClient lettuceClient2 = RedisClient.create("redis://localhost:6381");

// This works, but stick to one client type for consistency
Redlock redlock = new Redlock(jedisPool, lettuceClient1, lettuceClient2);
```

!!! warning "Consistency Recommendation"
    While mixing clients is technically possible, it's recommended to use the same client type for all Redis instances for consistency and easier maintenance.

## Connection Management

### Jedis

```java
// Always close pools when done
try {
    // Use redlock
} finally {
    pool1.close();
    pool2.close();
    pool3.close();
}
```

### Lettuce

```java
// Shutdown clients and resources
try {
    // Use redlock
} finally {
    client1.shutdown();
    client2.shutdown();
    client3.shutdown();
    resources.shutdown();
}
```

## Next Steps

- [Best Practices](best-practices.md) - Follow recommended practices
- [Configuration](../api/configuration.md) - Detailed configuration options

