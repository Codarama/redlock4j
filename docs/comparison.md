# Redlock4j vs Redisson

Comparison between Redlock4j and Redisson for distributed locking.

## Quick Comparison

| Feature | Redlock4j | Redisson |
|---------|-----------|----------|
| **Algorithm** | Pure Redlock | Single instance / Master-Slave |
| **API** | Standard Java Lock | Custom RLock |
| **Dependencies** | Minimal | Netty + many others |
| **Size** | ~10 core classes | 50+ data structures |
| **Focus** | Distributed locking | Full Redis framework |
| **Learning Curve** | Low | Medium-High |
| **Redis Clients** | Jedis or Lettuce | Built-in Netty client |

## Architecture

### Redlock4j

- **Pure Redlock Implementation**: Follows the official Redlock algorithm
- **Quorum-Based**: Requires majority (N/2+1) of Redis nodes
- **Multi-Master**: Works with independent Redis instances
- **Fault Tolerant**: Survives minority node failures

### Redisson

- **Single Instance**: Standard RLock uses single Redis
- **Master-Slave**: RedissonRedLock available but not default
- **Full Framework**: Comprehensive Redis client library
- **Feature Rich**: Many data structures beyond locks

## API Comparison

### Redlock4j - Standard Java Lock

```java
Lock lock = manager.createLock("resource");
lock.lock();
try {
    // Critical section
} finally {
    lock.unlock();
}
```

**Advantages:**
- Familiar Java API
- Drop-in replacement for `java.util.concurrent.locks.Lock`
- No learning curve

### Redisson - Custom RLock

```java
RLock lock = redisson.getLock("resource");
lock.lock();
try {
    // Critical section
} finally {
    lock.unlock();
}
```

**Differences:**
- Custom `RLock` interface
- Additional methods (tryLock with lease time, etc.)
- Redisson-specific API

## Safety Guarantees

### Redlock4j

✅ **True Distributed Lock**
- Requires majority of nodes to acquire lock
- Survives minority node failures
- Clock drift compensation
- No single point of failure

### Redisson Standard RLock

⚠️ **Single Instance Lock**
- Uses single Redis instance
- Master failure = lock unavailable
- Replication lag can cause issues
- Single point of failure

### Redisson RedissonRedLock

✅ **Redlock Implementation**
- Similar to Redlock4j
- Requires explicit use of `RedissonRedLock`
- Not the default locking mechanism

## Dependencies

### Redlock4j

```xml
<dependency>
    <groupId>org.codarama</groupId>
    <artifactId>redlock4j</artifactId>
    <version>1.1.0</version>
</dependency>
<!-- Plus your choice of Jedis or Lettuce -->
```

**Size:** ~50KB
**Dependencies:** Minimal (SLF4J + Redis client)

### Redisson

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>3.x.x</version>
</dependency>
```

**Size:** Several MB
**Dependencies:** Netty, Jackson, many others

## Use Cases

### Choose Redlock4j When:

- ✅ You need **true distributed locking**
- ✅ You want **minimal dependencies**
- ✅ You prefer **standard Java APIs**
- ✅ You need **multi-master setup**
- ✅ You want **lightweight library**

### Choose Redisson When:

- ✅ You need **many Redis data structures**
- ✅ You want **all-in-one framework**
- ✅ You need **reactive/async APIs**
- ✅ You use **Redis Cluster/Sentinel**
- ✅ Single instance locking is sufficient

## Performance

### Redlock4j

- **Latency**: Higher (requires majority quorum)
- **Throughput**: Moderate (multiple Redis calls)
- **Network**: More network calls
- **Best For**: Safety over speed

### Redisson

- **Latency**: Lower (single instance)
- **Throughput**: Higher (single call)
- **Network**: Fewer network calls
- **Best For**: Speed over safety

## Migration

### From Redisson to Redlock4j

```java
// Before (Redisson)
RLock lock = redisson.getLock("resource");
lock.lock();
try {
    // work
} finally {
    lock.unlock();
}

// After (Redlock4j)
Lock lock = manager.createLock("resource");
lock.lock();
try {
    // work
} finally {
    lock.unlock();
}
```

### From Redlock4j to Redisson

```java
// Before (Redlock4j)
Lock lock = manager.createLock("resource");
lock.lock();

// After (Redisson RedissonRedLock)
RLock lock1 = redisson1.getLock("resource");
RLock lock2 = redisson2.getLock("resource");
RLock lock3 = redisson3.getLock("resource");
RedissonRedLock lock = new RedissonRedLock(lock1, lock2, lock3);
lock.lock();
```

## Detailed Comparisons

For in-depth technical comparisons of specific features:

- **[FairLock Implementation](comparison/fairlock-implementation.md)** - Detailed comparison of FairLock implementations between redlock4j and Redisson, including data structures, algorithms, and trade-offs
- **[MultiLock Implementation](comparison/multilock-implementation.md)** - Comprehensive comparison of MultiLock implementations, covering deadlock prevention, acquisition strategies, and use cases
- **[Semaphore Implementation](comparison/semaphore-implementation.md)** - In-depth comparison of distributed semaphore implementations, analyzing permit management, performance, and consistency models
- **[ReadWriteLock Implementation](comparison/readwritelock-implementation.md)** - Detailed comparison of read-write lock implementations, covering reader/writer coordination, lock upgrade/downgrade, and performance characteristics
- **[CountDownLatch Implementation](comparison/countdownlatch-implementation.md)** - Comprehensive comparison of countdown latch implementations, analyzing counting mechanisms, notification strategies, and consistency guarantees

## Conclusion

**Redlock4j** is ideal for applications that:
- Need true distributed locking guarantees
- Want minimal dependencies
- Prefer standard Java APIs
- Value simplicity and focus

**Redisson** is ideal for applications that:
- Need comprehensive Redis functionality
- Want a full-featured framework
- Require reactive/async support
- Can accept single-instance locking

For complete comparison, see [COMPARISON.md](https://github.com/codarama/redlock4j/blob/main/COMPARISON.md) in the repository.

