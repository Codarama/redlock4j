# Best Practices

Follow these best practices to use Redlock4j effectively and safely in production.

## Lock Management

### Always Release Locks

Always release locks in a `finally` block:

```java
Lock lock = redlock.lock("resource", 10000);
if (lock != null) {
    try {
        // Critical section
    } finally {
        redlock.unlock(lock);  // Always execute
    }
}
```

### Check Lock Acquisition

Always check if lock acquisition succeeded:

```java
Lock lock = redlock.lock("resource", 10000);
if (lock != null) {
    // Lock acquired successfully
} else {
    // Failed to acquire lock - handle appropriately
    handleLockFailure();
}
```

### Use Appropriate TTL

Set TTL longer than your operation:

```java
// Bad: TTL too short
Lock lock = redlock.lock("resource", 1000);  // 1 second
performLongOperation();  // Takes 5 seconds - lock will expire!

// Good: TTL with safety margin
Lock lock = redlock.lock("resource", 10000);  // 10 seconds
performLongOperation();  // Takes 5 seconds - safe
```

## Redis Configuration

### Use Independent Redis Instances

For production, use truly independent Redis instances:

```java
// Good: Independent instances on different servers
Redlock redlock = new Redlock(
    new JedisPool("redis1.example.com", 6379),
    new JedisPool("redis2.example.com", 6379),
    new JedisPool("redis3.example.com", 6379)
);

// Bad: Master-slave replication (not independent)
// Don't use master and its slaves as separate instances
```

### Minimum 3 Instances

Always use at least 3 Redis instances:

```java
// Minimum for fault tolerance
Redlock redlock = new Redlock(pool1, pool2, pool3);

// Better: 5 instances for higher availability
Redlock redlock = new Redlock(pool1, pool2, pool3, pool4, pool5);
```

### Use Odd Numbers

Always use an odd number of instances:

- ✅ 3, 5, 7 instances
- ❌ 2, 4, 6 instances

## Error Handling

### Handle Lock Failures

```java
Lock lock = redlock.lock("resource", 10000);
if (lock == null) {
    // Log the failure
    logger.warn("Failed to acquire lock for resource");
    
    // Implement fallback strategy
    // Option 1: Retry later
    scheduleRetry();
    
    // Option 2: Return error to caller
    throw new LockAcquisitionException("Could not acquire lock");
    
    // Option 3: Use alternative approach
    performAlternativeOperation();
}
```

### Handle Exceptions

```java
Lock lock = null;
try {
    lock = redlock.lock("resource", 10000);
    if (lock != null) {
        performCriticalOperation();
    }
} catch (Exception e) {
    logger.error("Error in critical section", e);
    handleError(e);
} finally {
    if (lock != null) {
        try {
            redlock.unlock(lock);
        } catch (Exception e) {
            logger.error("Error releasing lock", e);
            // Don't throw - we're in finally block
        }
    }
}
```

## Performance

### Reuse Redlock Instances

Create Redlock instances once and reuse:

```java
// Good: Singleton pattern
public class LockService {
    private static final Redlock REDLOCK = createRedlock();
    
    public Lock acquireLock(String resource) {
        return REDLOCK.lock(resource, 10000);
    }
}

// Bad: Creating new instance each time
public Lock acquireLock(String resource) {
    Redlock redlock = new Redlock(pool1, pool2, pool3);  // Wasteful!
    return redlock.lock(resource, 10000);
}
```

### Configure Connection Pools

Properly configure connection pools:

```java
JedisPoolConfig config = new JedisPoolConfig();
config.setMaxTotal(128);        // Enough for your load
config.setMaxIdle(64);          // Keep some idle connections
config.setMinIdle(16);          // Minimum ready connections
config.setTestOnBorrow(true);   // Validate connections
config.setTestWhileIdle(true);  // Clean up stale connections
```

### Use Appropriate Retry Settings

```java
RedlockConfiguration config = RedlockConfiguration.builder()
    .retryCount(3)      // Don't retry too many times
    .retryDelay(200)    // Reasonable delay between retries
    .build();
```

## Resource Naming

### Use Descriptive Names

```java
// Good: Clear and descriptive
Lock lock = redlock.lock("user:123:profile:update", 10000);
Lock lock = redlock.lock("order:456:payment:process", 10000);

// Bad: Unclear names
Lock lock = redlock.lock("lock1", 10000);
Lock lock = redlock.lock("temp", 10000);
```

### Use Consistent Naming Convention

```java
// Establish a pattern
String lockKey = String.format("%s:%s:%s", 
    entityType,    // "user", "order", "product"
    entityId,      // "123", "456"
    operation      // "update", "delete", "process"
);
```

## Monitoring and Logging

### Log Lock Operations

```java
Lock lock = redlock.lock(resourceId, ttl);
if (lock != null) {
    logger.info("Acquired lock for resource: {}", resourceId);
    try {
        performOperation();
    } finally {
        redlock.unlock(lock);
        logger.info("Released lock for resource: {}", resourceId);
    }
} else {
    logger.warn("Failed to acquire lock for resource: {}", resourceId);
}
```

### Monitor Lock Metrics

Track important metrics:

- Lock acquisition success rate
- Lock acquisition latency
- Lock hold time
- Lock contention rate

## Testing

### Test Lock Behavior

```java
@Test
public void testLockAcquisition() {
    Lock lock = redlock.lock("test-resource", 10000);
    assertNotNull(lock, "Should acquire lock");
    
    // Try to acquire same lock - should fail
    Lock lock2 = redlock.tryLock("test-resource", 10000);
    assertNull(lock2, "Should not acquire already locked resource");
    
    redlock.unlock(lock);
}
```

### Use Testcontainers

For integration tests:

```java
@Testcontainers
public class RedlockIntegrationTest {
    @Container
    private static GenericContainer<?> redis = 
        new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);
    
    @Test
    public void testWithRealRedis() {
        JedisPool pool = new JedisPool(
            redis.getHost(), 
            redis.getFirstMappedPort()
        );
        Redlock redlock = new Redlock(pool);
        // Test with real Redis
    }
}
```

## Common Pitfalls

### ❌ Don't Forget to Unlock

```java
// Bad: No unlock
Lock lock = redlock.lock("resource", 10000);
performOperation();  // If this throws, lock is never released!
```

### ❌ Don't Use Same Redis Instance Multiple Times

```java
// Bad: Same instance counted 3 times
Redlock redlock = new Redlock(pool, pool, pool);  // Wrong!
```

### ❌ Don't Ignore Lock Acquisition Failures

```java
// Bad: Assuming lock is always acquired
Lock lock = redlock.lock("resource", 10000);
performOperation();  // What if lock is null?
```

## Next Steps

- [API Reference](../api/core.md) - Detailed API documentation
- [Advanced Locking](advanced-locking.md) - Advanced features

