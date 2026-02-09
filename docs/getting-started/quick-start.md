# Quick Start

This guide will help you get started with Redlock4j in just a few minutes.

## Basic Setup

### 1. Create Redis Connection Pools

First, create connection pools for your Redis instances. For production use, you should have at least 3 independent Redis instances.

```java
import redis.clients.jedis.JedisPool;

// Create pools for multiple Redis instances
JedisPool pool1 = new JedisPool("redis1.example.com", 6379);
JedisPool pool2 = new JedisPool("redis2.example.com", 6379);
JedisPool pool3 = new JedisPool("redis3.example.com", 6379);
```

### 2. Create a Redlock Instance

```java
import org.codarama.redlock4j.Redlock;

Redlock redlock = new Redlock(pool1, pool2, pool3);
```

### 3. Acquire and Release Locks

```java
import org.codarama.redlock4j.Lock;

// Try to acquire a lock for "my-resource" with 10 second TTL
Lock lock = redlock.lock("my-resource", 10000);

if (lock != null) {
    try {
        // Lock acquired successfully
        // Perform your critical section here
        System.out.println("Lock acquired! Performing critical operation...");
        performCriticalOperation();
    } finally {
        // Always release the lock
        redlock.unlock(lock);
        System.out.println("Lock released");
    }
} else {
    // Failed to acquire lock
    System.out.println("Could not acquire lock");
}
```

## Complete Example

Here's a complete working example:

```java
import org.codarama.redlock4j.Redlock;
import org.codarama.redlock4j.Lock;
import redis.clients.jedis.JedisPool;

public class RedlockExample {
    public static void main(String[] args) {
        // Setup Redis pools
        JedisPool pool1 = new JedisPool("localhost", 6379);
        JedisPool pool2 = new JedisPool("localhost", 6380);
        JedisPool pool3 = new JedisPool("localhost", 6381);
        
        // Create Redlock instance
        Redlock redlock = new Redlock(pool1, pool2, pool3);
        
        // Resource identifier
        String resourceId = "shared-resource";
        
        // Lock TTL in milliseconds (10 seconds)
        int ttl = 10000;
        
        // Try to acquire lock
        Lock lock = redlock.lock(resourceId, ttl);
        
        if (lock != null) {
            try {
                // Critical section
                System.out.println("Processing shared resource...");
                Thread.sleep(2000); // Simulate work
                System.out.println("Done processing");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                // Release lock
                redlock.unlock(lock);
            }
        } else {
            System.out.println("Another process is using the resource");
        }
        
        // Cleanup
        pool1.close();
        pool2.close();
        pool3.close();
    }
}
```

## Using Lettuce Instead of Jedis

If you prefer Lettuce over Jedis:

```java
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.codarama.redlock4j.Redlock;

// Create Lettuce clients
RedisClient client1 = RedisClient.create("redis://localhost:6379");
RedisClient client2 = RedisClient.create("redis://localhost:6380");
RedisClient client3 = RedisClient.create("redis://localhost:6381");

// Create Redlock instance
Redlock redlock = new Redlock(client1, client2, client3);

// Use the same lock/unlock pattern as above
```

## Important Notes

!!! warning "Lock TTL"
    Always set a TTL that's longer than your critical section execution time. If the lock expires while you're still processing, another client might acquire the lock.

!!! tip "Always Unlock"
    Always release locks in a `finally` block to ensure they're released even if an exception occurs.

!!! info "Minimum Redis Instances"
    For production use, always use at least 3 independent Redis instances to ensure proper fault tolerance.

## Next Steps

- [Basic Usage](../guide/basic-usage.md) - Explore more usage patterns
- [Advanced Locking](../guide/advanced-locking.md) - Learn about advanced features

