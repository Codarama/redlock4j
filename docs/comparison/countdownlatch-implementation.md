# CountDownLatch Implementation Comparison: redlock4j vs Redisson

This document provides a detailed technical comparison of the CountDownLatch implementations in redlock4j and Redisson.

## Overview

Both libraries implement distributed countdown latches for coordinating multiple threads/processes, but they use different approaches for counting and notification.

## Purpose & Use Case

### redlock4j RedlockCountDownLatch

**Purpose**: Distributed countdown latch with quorum-based consistency

**Use Case**: Coordinating distributed processes with strong consistency requirements

```java
// Create latch waiting for 3 operations
RedlockCountDownLatch latch = new RedlockCountDownLatch(
    "startup", 3, redisDrivers, config
);

// Worker threads
new Thread(() -> {
    initializeService1();
    latch.countDown();
}).start();

new Thread(() -> {
    initializeService2();
    latch.countDown();
}).start();

new Thread(() -> {
    initializeService3();
    latch.countDown();
}).start();

// Main thread waits
latch.await(); // Blocks until count reaches 0
System.out.println("All services initialized!");
```

### Redisson RedissonCountDownLatch

**Purpose**: Distributed countdown latch with pub/sub notifications

**Use Case**: High-performance coordination with single Redis instance

```java
RCountDownLatch latch = redisson.getCountDownLatch("startup");
latch.trySetCount(3);

// Worker threads
new Thread(() -> {
    initializeService1();
    latch.countDown();
}).start();

// ... more workers

// Main thread waits
latch.await();
System.out.println("All services initialized!");
```

## Architecture & Data Model

### redlock4j

**Design**: Counter with quorum-based reads/writes and pub/sub

**Data Structure**:
```
{latchKey} = {count}                    (counter on each node)
{latchKey}:channel = (pub/sub channel)  (notification channel)
```

**Key Characteristics**:
- Counter replicated across all nodes
- Quorum-based count reads
- Quorum-based decrements
- Pub/sub for zero notification
- Local latch for waiting
- Automatic expiration (10x lock timeout)

**Architecture**:
```
RedlockCountDownLatch
  ├─ latchKey (counter key)
  ├─ channelKey (pub/sub)
  ├─ List<RedisDriver> (quorum-based)
  ├─ CountDownLatch localLatch (for waiting)
  ├─ AtomicBoolean subscribed
  └─ Quorum-based DECR
```

### Redisson

**Design**: Single counter with pub/sub and Lua scripts

**Data Structure**:
```
{latchKey} = {count}                                    (single counter)
redisson_countdownlatch__channel__{latchKey} = (pub/sub channel)
```

**Key Characteristics**:
- Single counter (not replicated)
- Atomic Lua scripts
- Pub/sub for notifications
- Async/reactive support
- No automatic expiration

**Architecture**:
```
RedissonCountDownLatch
  ├─ latchKey (counter key)
  ├─ channelName (pub/sub)
  ├─ CountDownLatchPubSub (notification handler)
  ├─ Lua scripts for atomicity
  └─ Async futures
```

## Initialization

### redlock4j

**Initialization**: Automatic in constructor

```java
public RedlockCountDownLatch(String latchKey, int count,
                             List<RedisDriver> redisDrivers,
                             RedlockConfiguration config) {
    this.latchKey = latchKey;
    this.channelKey = latchKey + ":channel";
    this.initialCount = count;
    this.localLatch = new CountDownLatch(1);

    // Initialize on all nodes
    initializeLatch(count);
}

private void initializeLatch(int count) {
    String countValue = String.valueOf(count);
    int successfulNodes = 0;

    for (RedisDriver driver : redisDrivers) {
        // Set with long expiration (10x lock timeout)
        driver.setex(latchKey, countValue,


## Count Down Operation

### redlock4j

**Algorithm**: Quorum-based DECR with notification

```java
public void countDown() {
    int successfulNodes = 0;
    long newCount = -1;

    // Decrement on all nodes
    for (RedisDriver driver : redisDrivers) {
        try {
            long count = driver.decr(latchKey);
            newCount = count;
            successfulNodes++;
        } catch (Exception e) {
            logger.debug("Failed to decrement on {}", driver.getIdentifier());
        }
    }

    // Check quorum
    if (successfulNodes >= config.getQuorum()) {
        // If reached zero, publish notification
        if (newCount <= 0) {
            publishZeroNotification();
        }
    } else {
        logger.warn("Failed to decrement on quorum");
    }
}

private void publishZeroNotification() {
    for (RedisDriver driver : redisDrivers) {
        try {
            long subscribers = driver.publish(channelKey, "zero");
            logger.debug("Published to {} subscribers", subscribers);
        } catch (Exception e) {
            logger.debug("Failed to publish on {}", driver.getIdentifier());
        }
    }
}
```

**Characteristics**:
- DECR on all nodes
- Quorum check for success
- Publish to all nodes when zero
- No atomicity between decrement and publish

**Redis Operations** (M nodes):
- M × `DECR`
- M × `PUBLISH` (if zero)

### Redisson

**Algorithm**: Atomic Lua script with notification

```lua
-- countDownAsync
local v = redis.call('decr', KEYS[1]);
if v <= 0 then
    redis.call('del', KEYS[1])
end;
if v == 0 then
    redis.call(ARGV[2], KEYS[2], ARGV[1])
end;
```

**Characteristics**:
- Atomic decrement + delete + publish
- Single operation
- Deletes key when zero
- Publishes ZERO_COUNT_MESSAGE

**Redis Operations**:
- 1 Lua script execution

## Await Operation

### redlock4j

**Algorithm**: Subscribe + poll with local latch

```java
public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
    // Subscribe to notifications
    subscribeToNotifications();

    // Check if already zero
    long currentCount = getCount();
    if (currentCount <= 0) {
        return true;
    }

    // Wait on local latch (released by pub/sub notification)
    boolean completed = localLatch.await(timeout, unit);

    return completed;
}

private void subscribeToNotifications() {
    if (subscribed.compareAndSet(false, true)) {
        new Thread(() -> {
            // Subscribe to first driver
            RedisDriver driver = redisDrivers.get(0);
            driver.subscribe(new MessageHandler() {
                @Override
                public void onMessage(String channel, String message) {
                    if ("zero".equals(message)) {
                        localLatch.countDown(); // Release waiters
                    }
                }
            }, channelKey);
        }).start();
    }
}

public long getCount() {
    int successfulReads = 0;
    long totalCount = 0;

    for (RedisDriver driver : redisDrivers) {
        String countStr = driver.get(latchKey);
        if (countStr != null) {
            totalCount += Long.parseLong(countStr);
            successfulReads++;
        }
    }

    if (successfulReads >= config.getQuorum()) {
        return Math.max(0, totalCount / successfulReads);
    }

    return 0; // Conservative fallback
}
```

**Characteristics**:
- Subscribe to single driver (first one)
- Check count via quorum read
- Wait on local CountDownLatch
- Pub/sub releases local latch
- Average count across nodes

**Redis Operations** (M nodes):
- 1 × `SUBSCRIBE`
- M × `GET` (check count)

### Redisson

**Algorithm**: Subscribe + async polling

```java
public void await() throws InterruptedException {
    if (getCount() == 0) {
        return;
    }

    CompletableFuture<RedissonCountDownLatchEntry> future = subscribe();
    RedissonCountDownLatchEntry entry = future.join();

    try {
        while (getCount() > 0) {
            entry.getLatch().await(); // Wait for notification
        }
    } finally {
        unsubscribe(entry);
    }
}

private CompletableFuture<RedissonCountDownLatchEntry> subscribe() {
    return pubSub.subscribe(getEntryName(), getChannelName());
}

public long getCount() {
    return commandExecutor.writeAsync(
        getRawName(), LongCodec.INSTANCE,
        RedisCommands.GET_LONG, getRawName()
    ).join();
}
```

**Characteristics**:
- Subscribe via pub/sub service
- Poll count in loop
- Semaphore-based waiting
- Async futures
- Unsubscribe when done

**Redis Operations**:
- 1 × `SUBSCRIBE`
- N × `GET` (polling)

## Reset Operation

### redlock4j

**Reset**: Supported (non-standard)

```java
public void reset() {
    // Delete existing latch
    for (RedisDriver driver : redisDrivers) {
        driver.del(latchKey);
    }

    // Reset local state
    localLatch = new CountDownLatch(1);
    subscribed.set(false);

    // Reinitialize
    initializeLatch(initialCount);
}
```

**Characteristics**:
- Deletes on all nodes
- Resets local latch
- Reinitializes with original count
- Not atomic
- Can cause race conditions

### Redisson

**Reset**: Supported via `trySetCount()`

```java
// Delete old latch
latch.delete();

// Create new one
latch.trySetCount(initialCount);
```

**Characteristics**:
- Must explicitly delete first
- Then set new count
- Two separate operations
- Not atomic
- Publishes notifications

## Performance Comparison

### redlock4j

**Count Down** (M nodes):
- M × `DECR`
- M × `PUBLISH` (if zero)
- Total: M or 2M operations

**Await**:
- 1 × `SUBSCRIBE`
- M × `GET` (quorum read)
- Total: M+1 operations

**Complexity**: O(M) per operation

**Latency**:
- Higher due to quorum
- Multiple round trips
- Pub/sub to all nodes

### Redisson

**Count Down**:
- 1 Lua script execution
- Total: 1 operation

**Await**:
- 1 × `SUBSCRIBE`
- N × `GET` (polling)
- Total: N+1 operations

**Complexity**: O(1) for countDown, O(N) for await

**Latency**:
- Lower for single instance
- Single round trip for countDown
- Polling overhead for await

## Safety & Correctness

### redlock4j

**Safety Guarantees**:
- ✅ Quorum-based consistency
- ✅ Survives minority node failures
- ✅ Count averaged across nodes
- ✅ Automatic expiration
- ✅ No single point of failure

**Potential Issues**:
- ⚠️ Higher latency
- ⚠️ More network overhead
- ⚠️ Non-atomic decrement + publish
- ⚠️ Subscribe to single node only
- ⚠️ Count averaging may be inaccurate
- ⚠️ Reset not atomic

**Consistency Model**:
```
Count decremented if:
  - Quorum of nodes decremented
  - Average count used for reads

Notification sent if:
  - Any node reaches zero
  - Published to all nodes
```


### Redisson

**Safety Guarantees**:
- ✅ Atomic operations (Lua scripts)
- ✅ Accurate count
- ✅ Pub/sub notifications
- ✅ Async/reactive support
- ✅ Low latency

**Potential Issues**:
- ⚠️ Single point of failure
- ⚠️ No quorum mechanism
- ⚠️ No automatic expiration
- ⚠️ Polling in await loop
- ⚠️ Reset not atomic

**Consistency Model**:
```
Count decremented if:
  - Atomic Lua script succeeds
  - Single instance

Notification sent if:
  - Count reaches exactly zero
  - Atomic with decrement
```

## Feature Comparison Table

| Feature | redlock4j | Redisson |
|---------|-----------|----------|
| **Data Model** | Counter on all nodes | Single counter |
| **Quorum** | Yes | No |
| **Fault Tolerance** | Survives minority failures | Single point of failure |
| **Initialization** | Automatic in constructor | Explicit via trySetCount() |
| **Expiration** | Automatic (10x timeout) | No automatic expiration |
| **Count Accuracy** | Average across nodes | Exact |
| **Atomicity** | Non-atomic (DECR + PUBLISH) | Atomic (Lua script) |
| **Subscription** | Single node | Managed pub/sub service |
| **Reset** | Supported (non-standard) | Supported via delete + trySetCount |
| **Async Support** | No | Yes |
| **Reactive Support** | No | Yes |
| **Performance** | O(M) | O(1) for countDown |
| **Latency** | Higher | Lower |
| **Network Overhead** | High | Low |

## Use Case Comparison

### redlock4j RedlockCountDownLatch

**Best For**:
- Distributed systems requiring quorum-based safety
- Coordination with strong consistency
- Multi-master Redis setups
- Fault-tolerant coordination
- Automatic expiration needed

**Example Scenarios**:
```java
// Distributed service startup coordination
RedlockCountDownLatch startupLatch = new RedlockCountDownLatch(
    "app:startup", 5, redisDrivers, config
);

// Batch job coordination
RedlockCountDownLatch batchLatch = new RedlockCountDownLatch(
    "batch:job:123", 100, redisDrivers, config
);

// Multi-stage workflow
RedlockCountDownLatch stageLatch = new RedlockCountDownLatch(
    "workflow:stage1", 10, redisDrivers, config
);
```

### Redisson RedissonCountDownLatch

**Best For**:
- Single Redis instance deployments
- High-throughput coordination
- Applications needing async/reactive APIs
- Scenarios requiring exact count
- Low-latency requirements

**Example Scenarios**:
```java
// High-performance service coordination
RCountDownLatch startupLatch = redisson.getCountDownLatch("app:startup");
startupLatch.trySetCount(5);

// Async coordination
RCountDownLatch asyncLatch = redisson.getCountDownLatch("async:task");
asyncLatch.trySetCount(10);
RFuture<Void> future = asyncLatch.awaitAsync();

// Reusable latch
RCountDownLatch reusableLatch = redisson.getCountDownLatch("reusable");
reusableLatch.trySetCount(3);
// ... use it
reusableLatch.delete();
reusableLatch.trySetCount(5); // Reuse
```

## Recommendations

### Choose redlock4j RedlockCountDownLatch when:

- ✅ Need quorum-based distributed consistency
- ✅ Require fault tolerance (multi-master)
- ✅ Automatic expiration is important
- ✅ Can tolerate higher latency
- ✅ Count averaging is acceptable

### Choose Redisson RedissonCountDownLatch when:

- ✅ Single Redis instance is acceptable
- ✅ Need high throughput / low latency
- ✅ Require exact count tracking
- ✅ Need async/reactive APIs
- ✅ Want atomic operations
- ✅ Explicit initialization preferred

## Migration Considerations

### From Redisson to redlock4j

```java
// Before (Redisson)
RCountDownLatch latch = redisson.getCountDownLatch("startup");
latch.trySetCount(3);
latch.await();

// After (redlock4j)
RedlockCountDownLatch latch = new RedlockCountDownLatch(
    "startup", 3, redisDrivers, config
);
latch.await();
```

**Benefits**:
- Quorum-based safety
- Fault tolerance
- Automatic expiration

**Considerations**:
- Higher latency
- Count is averaged
- No async support

### From redlock4j to Redisson

```java
// Before (redlock4j)
RedlockCountDownLatch latch = new RedlockCountDownLatch(
    "startup", 3, redisDrivers, config
);

// After (Redisson)
RCountDownLatch latch = redisson.getCountDownLatch("startup");
latch.trySetCount(3);
```

**Benefits**:
- Lower latency
- Exact count
- Async/reactive support
- Atomic operations

**Considerations**:
- Single point of failure
- Must initialize explicitly
- No automatic expiration

## Conclusion

Both implementations provide distributed countdown latches with different trade-offs:

**redlock4j RedlockCountDownLatch**:
- Quorum-based with fault tolerance
- Automatic initialization and expiration
- Higher latency but survives failures
- Count averaged across nodes
- Best for multi-master setups requiring strong consistency

**Redisson RedissonCountDownLatch**:
- Atomic Lua scripts with exact counting
- Lower latency but single point of failure
- Pub/sub notifications
- Async/reactive support
- Best for high-throughput single-instance deployments

Choose based on your specific requirements:
- **Distributed consistency & fault tolerance** → redlock4j RedlockCountDownLatch
- **High throughput & low latency** → Redisson RedissonCountDownLatch
- **Exact count tracking** → Redisson RedissonCountDownLatch
- **Automatic expiration** → redlock4j RedlockCountDownLatch


