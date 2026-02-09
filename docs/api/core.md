# Core API Reference

This page documents the core API of Redlock4j.

## Redlock Class

The main class for creating and managing distributed locks.

### Constructor

```java
public Redlock(JedisPool... pools)
public Redlock(RedisClient... clients)
public Redlock(RedlockConfiguration config, JedisPool... pools)
public Redlock(RedlockConfiguration config, RedisClient... clients)
```

**Parameters:**
- `config` - Configuration for Redlock behavior (optional)
- `pools` - Jedis connection pools for Redis instances
- `clients` - Lettuce Redis clients

**Example:**
```java
Redlock redlock = new Redlock(pool1, pool2, pool3);
```

### lock()

Acquire a lock with retries.

```java
public Lock lock(String resource, int ttl)
public Lock lock(String resource, int ttl, int timeout)
public Lock lock(String[] resources, int ttl)
```

**Parameters:**
- `resource` - Resource identifier to lock
- `resources` - Multiple resources to lock atomically
- `ttl` - Time-to-live in milliseconds
- `timeout` - Maximum time to wait for lock acquisition (optional)

**Returns:**
- `Lock` object if successful, `null` if failed

**Example:**
```java
Lock lock = redlock.lock("my-resource", 10000);
```

### tryLock()

Attempt to acquire a lock without retries.

```java
public Lock tryLock(String resource, int ttl)
```

**Parameters:**
- `resource` - Resource identifier to lock
- `ttl` - Time-to-live in milliseconds

**Returns:**
- `Lock` object if successful, `null` if failed

**Example:**
```java
Lock lock = redlock.tryLock("my-resource", 10000);
```

### unlock()

Release a previously acquired lock.

```java
public void unlock(Lock lock)
```

**Parameters:**
- `lock` - Lock object to release

**Example:**
```java
redlock.unlock(lock);
```

### extend()

Extend the TTL of an existing lock.

```java
public boolean extend(Lock lock, int additionalTtl)
```

**Parameters:**
- `lock` - Lock to extend
- `additionalTtl` - Additional time in milliseconds

**Returns:**
- `true` if extension successful, `false` otherwise

**Example:**
```java
boolean extended = redlock.extend(lock, 5000);
```

### isValid()

Check if a lock is still valid.

```java
public boolean isValid(Lock lock)
```

**Parameters:**
- `lock` - Lock to check

**Returns:**
- `true` if lock is still valid, `false` otherwise

**Example:**
```java
if (redlock.isValid(lock)) {
    // Lock is still valid
}
```

## Lock Interface

Represents a distributed lock.

### Methods

```java
public String getResource()
public String getValue()
public long getValidityTime()
```

**Example:**
```java
Lock lock = redlock.lock("resource", 10000);
String resource = lock.getResource();  // "resource"
String value = lock.getValue();        // Unique lock value
long validity = lock.getValidityTime(); // Remaining validity in ms
```

## RedlockConfiguration Class

Configuration for Redlock behavior.

### Builder

```java
RedlockConfiguration config = RedlockConfiguration.builder()
    .retryCount(3)
    .retryDelay(200)
    .clockDriftFactor(0.01)
    .build();
```

### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `retryCount` | int | 3 | Number of retry attempts |
| `retryDelay` | int | 200 | Delay between retries (ms) |
| `clockDriftFactor` | double | 0.01 | Clock drift compensation factor |

**Example:**
```java
RedlockConfiguration config = RedlockConfiguration.builder()
    .retryCount(5)
    .retryDelay(100)
    .clockDriftFactor(0.02)
    .build();

Redlock redlock = new Redlock(config, pool1, pool2, pool3);
```

## Exceptions

### RedlockException

Base exception for Redlock operations.

```java
try {
    Lock lock = redlock.lock("resource", 10000);
} catch (RedlockException e) {
    logger.error("Redlock error", e);
}
```

### LockAcquisitionException

Thrown when lock acquisition fails critically.

```java
try {
    Lock lock = redlock.lock("resource", 10000);
} catch (LockAcquisitionException e) {
    logger.error("Failed to acquire lock", e);
}
```

## Thread Safety

All Redlock methods are thread-safe and can be called concurrently from multiple threads.

```java
// Safe to use from multiple threads
Redlock redlock = new Redlock(pool1, pool2, pool3);

// Thread 1
Lock lock1 = redlock.lock("resource-1", 10000);

// Thread 2 (concurrent)
Lock lock2 = redlock.lock("resource-2", 10000);
```

## Next Steps

- [Configuration API](configuration.md) - Configuration details
- [Basic Usage](../guide/basic-usage.md) - Usage examples

