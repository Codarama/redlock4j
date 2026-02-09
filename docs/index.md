# Redlock4j

A robust Java implementation of the [Redlock distributed locking algorithm](https://redis.io/topics/distlock) for Redis.

## Overview

Redlock4j provides a reliable distributed locking mechanism using Redis, implementing the Redlock algorithm proposed by Redis creator Antirez. It ensures mutual exclusion across distributed systems with high availability and fault tolerance.

## Key Features

- **Pure [Redlock distributed locking algorithm](https://redis.io/docs/latest/develop/use/patterns/distributed-locks/)** - entirely based on Redis definition
- **Multiple Redis Drivers**: Integrated supports for [Jedis](https://github.com/redis/jedis) and [Lettuce](https://github.com/redis/lettuce), extensible to other drivers
- **Lightweight** - Minimum implementation, no extra scope outside locking
- **Multi-interface API** - Supports standard [Lock](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/locks/Lock.html), as well as async and reactive APIs
- **Advanced Locking**: Fair, multi & read-write locks, semaphores, and countdown latches
- **Lock Extension**: Extend lock validity time without releasing and re-acquiring
- **Atomic CAS/CAD**: Auto-detects native [Redis 8.4+ CAS/CAD commands](https://redis.io/docs/latest/operate/oss_and_stack/stack-with-enterprise/release-notes/redisce/redisos-8.4-release-notes/) when available
- **Java 8+** - Compatible with Java 8 and higher

## Quick Example

```java
// Create a Redlock instance
Redlock redlock = new Redlock(jedisPool1, jedisPool2, jedisPool3);

// Acquire a lock
Lock lock = redlock.lock("my-resource", 10000);

if (lock != null) {
    try {
        // Critical section - your protected code here
        performCriticalOperation();
    } finally {
        // Always unlock in a finally block
        redlock.unlock(lock);
    }
} else {
    // Failed to acquire lock
    handleLockFailure();
}
```

## Why Redlock4j?

### Distributed Lock Guarantees

Redlock4j provides the following safety and liveness guarantees:

1. **Mutual Exclusion** - At most one client can hold a lock at any given time
2. **Deadlock Free** - Eventually it's always possible to acquire a lock, even if the client that locked a resource crashes
3. **Fault Tolerance** - As long as the majority of Redis nodes are up, clients can acquire and release locks

### Use Cases

- **Distributed Task Scheduling** - Ensure only one instance processes a scheduled task
- **Resource Access Control** - Coordinate access to shared resources across services
- **Leader Election** - Implement leader election in distributed systems
- **Rate Limiting** - Implement distributed rate limiting
- **Cache Invalidation** - Coordinate cache updates across multiple instances

## Getting Started

Check out the [Installation Guide](getting-started/installation.md) to add Redlock4j to your project, or jump straight to the [Quick Start](getting-started/quick-start.md) to see it in action.

## License

Redlock4j is released under the [MIT License](https://opensource.org/licenses/MIT).

