# Semaphore Implementation Comparison: redlock4j vs Redisson

This document provides a detailed technical comparison of the Semaphore implementations in redlock4j and Redisson.

## Overview

Both libraries implement distributed semaphores to limit concurrent access to resources, but they use fundamentally different approaches and data structures.

## Purpose & Use Case

### redlock4j RedlockSemaphore

**Purpose**: Distributed semaphore with quorum-based safety guarantees

**Use Case**: Rate limiting and resource pooling with strong consistency requirements

```java
// Create a semaphore with 5 permits
RedlockSemaphore semaphore = new RedlockSemaphore(
    "api-limiter", 5, redisDrivers, config
);

// Acquire a permit
if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
    try {
        // Perform rate-limited operation
        callExternalAPI();
    } finally {
        semaphore.release();
    }
}
```

### Redisson RedissonSemaphore

**Purpose**: Distributed semaphore with pub/sub notification

**Use Case**: General-purpose semaphore with async support and efficient waiting

```java
RSemaphore semaphore = redisson.getSemaphore("api-limiter");
semaphore.trySetPermits(5);

// Acquire a permit
if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
    try {
        // Perform rate-limited operation
        callExternalAPI();
    } finally {
        semaphore.release();
    }
}
```

## Architecture & Data Model

### redlock4j

**Design**: Individual permit keys with quorum-based acquisition

**Data Structure**:
```
{semaphoreKey}:permit:{permitId1} = {permitId1}  (TTL: lockTimeout)
{semaphoreKey}:permit:{permitId2} = {permitId2}  (TTL: lockTimeout)
{semaphoreKey}:permit:{permitId3} = {permitId3}  (TTL: lockTimeout)
...
```

**Key Characteristics**:
- Each permit is a separate Redis key
- Permit acquisition uses `SET NX` (same as lock)
- Quorum required for each permit
- No central counter
- Thread-local state tracking

**Architecture**:
```
RedlockSemaphore
  ├─ semaphoreKey (base key)
  ├─ maxPermits (configured limit)
  ├─ List<RedisDriver> (quorum-based)
  └─ ThreadLocal<PermitState>
       ├─ List<String> permitIds
       ├─ acquisitionTime
       └─ validityTime
```

### Redisson

**Design**: Single counter with pub/sub notification

**Data Structure**:
```
{semaphoreKey} = {availablePermits}  (integer counter)
redisson_sc:{semaphoreKey} = (pub/sub channel)
```

**Key Characteristics**:
- Single Redis key stores available permit count
- Uses `DECRBY` for acquisition, `INCRBY` for release
- Pub/sub for efficient waiting
- No quorum mechanism
- Async/reactive support

**Architecture**:
```
RedissonSemaphore
  ├─ semaphoreKey (counter key)
  ├─ channelName (pub/sub)
  ├─ SemaphorePubSub (notification)
  └─ No thread-local state
```

## Permit Acquisition Algorithm

### redlock4j

**Algorithm**: Create individual permit keys with quorum

```java
private SemaphoreResult attemptAcquire(int permits) {
    List<String> permitIds = new ArrayList<>();

    // 1. For each permit needed
    for (int i = 0; i < permits; i++) {
        String permitId = generatePermitId();
        String permitKey = semaphoreKey + ":permit:" + permitId;

        // 2. Try to acquire on each Redis node
        int successfulNodes = 0;
        for (RedisDriver driver : redisDrivers) {
            if (driver.setIfNotExists(permitKey, permitId, timeout)) {
                successfulNodes++;
            }
        }

        // 3. Check quorum for this permit
        if (successfulNodes >= config.getQuorum()) {
            permitIds.add(permitId);
        } else {
            // Failed - rollback all permits
            releasePermits(permitIds);
            return new SemaphoreResult(false, 0, new ArrayList<>());
        }
    }

    // 4. Check validity time
    long validityTime = timeout - elapsedTime - driftTime;
    boolean acquired = permitIds.size() == permits && validityTime > 0;



### Redisson

**Algorithm**: Atomic counter decrement with Lua script

```lua
-- tryAcquireAsync0
local value = redis.call('get', KEYS[1]);
if (value ~= false and tonumber(value) >= tonumber(ARGV[1])) then
    local val = redis.call('decrby', KEYS[1], ARGV[1]);
    return 1;
end;
return 0;
```

**Flow**:
1. Get current permit count
2. Check if enough permits available
3. Atomically decrement counter
4. Return success/failure

**Redis Operations** (for N permits):
- 1 Lua script execution (atomic)
- If waiting: pub/sub subscription + notifications

**Waiting Mechanism**:
```java
public void acquire(int permits) throws InterruptedException {
    if (tryAcquire(permits)) {
        return; // Got it immediately
    }

    // Subscribe to notifications
    CompletableFuture<RedissonLockEntry> future = subscribe();
    RedissonLockEntry entry = future.get();

    try {
        while (true) {
            if (tryAcquire(permits)) {
                return; // Got it
            }

            // Wait for notification
            entry.getLatch().acquire();
        }
    } finally {
        unsubscribe(entry);
    }
}
```

## Permit Release Algorithm

### redlock4j

**Algorithm**: Delete individual permit keys

```java
private void releasePermits(List<String> permitIds) {
    for (String permitId : permitIds) {
        String permitKey = semaphoreKey + ":permit:" + permitId;

        // Delete on all nodes
        for (RedisDriver driver : redisDrivers) {
            driver.deleteIfValueMatches(permitKey, permitId);
        }
    }
}
```

**Characteristics**:
- Delete each permit key individually
- No notification to waiting threads
- Waiting threads discover via polling
- Quorum-based deletion

**Redis Operations** (for N permits on M nodes):
- N × M `DELETE` operations

### Redisson

**Algorithm**: Atomic counter increment with pub/sub notification

```lua
-- releaseAsync
local value = redis.call('incrby', KEYS[1], ARGV[1]);
redis.call(ARGV[2], KEYS[2], value);
```

**Characteristics**:
- Increment counter atomically
- Publish notification to channel
- Waiting threads wake up immediately
- Single Redis operation

**Redis Operations** (for N permits):
- 1 Lua script execution
- 1 `PUBLISH` to channel

**Notification Flow**:
```
Thread 1: acquire() → blocks → subscribes to channel
Thread 2: release() → INCRBY + PUBLISH
Thread 1: receives notification → wakes up → tryAcquire() → success
```

## Fairness & Ordering

### redlock4j

**Fairness**: Non-fair (no ordering guarantees)

**Characteristics**:
- Permits acquired in arbitrary order
- No queue for waiting threads
- Retry-based acquisition
- First to successfully acquire wins

**Example**:
```
Thread 1: tryAcquire() → retry → retry → success
Thread 2: tryAcquire() → success (may acquire before Thread 1)
Thread 3: tryAcquire() → retry → timeout
```

### Redisson

**Fairness**: Non-fair (explicitly documented)

**Characteristics**:
- No FIFO ordering
- Pub/sub wakes all waiters
- Race to acquire after notification
- First to execute Lua script wins

**Example**:
```
Thread 1: acquire() → blocks → subscribes
Thread 2: acquire() → blocks → subscribes
Thread 3: release() → PUBLISH
Thread 1 & 2: wake up → race to tryAcquire()
Winner: unpredictable
```

**Note**: Redisson also provides `RedissonPermitExpirableSemaphore` for fair semaphores with FIFO ordering.

## Permit Counting & Availability

### redlock4j

**Counting**: Implicit (count active permit keys)

```java
public int availablePermits() {
    // Note: This would require counting active permits across all nodes
    // Current implementation returns maxPermits (placeholder)
    return maxPermits;
}
```

**Challenges**:
- No central counter
- Would need to count keys matching pattern
- Expensive operation (SCAN on all nodes)
- Not implemented accurately

**Actual Available Permits**:
```
Available = maxPermits - (number of active permit keys with quorum)
```

### Redisson

**Counting**: Explicit counter

```java
public int availablePermits() {
    return get(availablePermitsAsync());
}

// Implementation
public RFuture<Integer> availablePermitsAsync() {
    return commandExecutor.writeAsync(
        getRawName(), LongCodec.INSTANCE,
        RedisCommands.GET_INTEGER, getRawName()
    );
}
```

**Characteristics**:
- Single `GET` operation
- Accurate and fast
- O(1) complexity
- Real-time availability

## Initialization & Configuration

### redlock4j

**Initialization**: Implicit (no setup required)

```java
// Create and use immediately
RedlockSemaphore semaphore = new RedlockSemaphore(
    "api-limiter", 5, redisDrivers, config
);

// No need to set permits - maxPermits is just a limit
semaphore.tryAcquire();
```

**Characteristics**:
- `maxPermits` is a configuration parameter
- No Redis initialization needed
- Permits created on-demand
- No way to "drain" or "reset" permits

### Redisson

**Initialization**: Explicit (must set permits)

```java
RSemaphore semaphore = redisson.getSemaphore("api-limiter");

// Must initialize before use
semaphore.trySetPermits(5);

// Or add permits
semaphore.addPermits(5);

// Now can use
semaphore.tryAcquire();
```

**Characteristics**:
- Must explicitly set initial permits
- `trySetPermits()` - sets only if not exists
- `addPermits()` - adds to existing count
- `drainPermits()` - removes all permits
- Can reset/reconfigure at runtime

**Additional Operations**:
```java
// Set permits with TTL
semaphore.trySetPermits(5, Duration.ofMinutes(10));

// Drain all permits
int drained = semaphore.drainPermits();

// Release even if not held (add permits)
semaphore.addPermits(3);
```

## Timeout & Validity

### redlock4j

**Validity**: Per-acquisition validity time

```java
private static class PermitState {
    final List<String> permitIds;
    final long acquisitionTime;
    final long validityTime; // Calculated validity

    boolean isValid() {
        return System.currentTimeMillis() < acquisitionTime + validityTime;
    }
}
```

**Characteristics**:
- Validity time calculated per acquisition
- Clock drift compensation
- Permits auto-expire via Redis TTL
- Thread-local validity tracking

**Validity Calculation**:
```java
long elapsedTime = System.currentTimeMillis() - startTime;
long driftTime = (long) (timeout * clockDriftFactor) + 2;
long validityTime = timeout - elapsedTime - driftTime;
```

### Redisson

**Validity**: No automatic expiration

**Characteristics**:
- Permits don't expire automatically
- Counter persists indefinitely
- Can set TTL on semaphore key explicitly
- No validity tracking per acquisition

**Optional TTL**:
```java
// Set permits with expiration
semaphore.trySetPermits(5, Duration.ofMinutes(10));

// After 10 minutes, the entire semaphore key expires
// All permits lost
```


## Performance Comparison

### redlock4j

**Acquisition** (N permits on M nodes):
- N × M `SET NX` operations
- Sequential per permit
- Parallel across nodes
- Rollback on failure: N × M `DELETE`

**Release** (N permits on M nodes):
- N × M `DELETE` operations
- No notification overhead

**Complexity**: O(N × M) per operation

**Latency**:
- Higher due to quorum requirement
- Multiple round trips per permit
- No pub/sub overhead

**Example** (3 permits, 3 nodes):
```
Acquire:
  Permit 1: SET on Node1, Node2, Node3 (parallel)
  Permit 2: SET on Node1, Node2, Node3 (parallel)
  Permit 3: SET on Node1, Node2, Node3 (parallel)
Total: 9 operations

Release:
  DELETE permit1 on Node1, Node2, Node3
  DELETE permit2 on Node1, Node2, Node3
  DELETE permit3 on Node1, Node2, Node3
Total: 9 operations
```

### Redisson

**Acquisition** (N permits):
- 1 Lua script execution (atomic)
- If waiting: 1 pub/sub subscription
- Notifications on release

**Release** (N permits):
- 1 Lua script execution
- 1 `PUBLISH` to channel

**Complexity**: O(1) per operation

**Latency**:
- Lower for single instance
- Single round trip
- Pub/sub notification overhead

**Example** (3 permits):
```
Acquire:
  1 Lua script: GET + DECRBY
  If blocked: subscribe to channel
Total: 1-2 operations

Release:
  1 Lua script: INCRBY + PUBLISH
Total: 1 operation
```

## Safety & Correctness

### redlock4j

**Safety Guarantees**:
- ✅ Quorum-based consistency
- ✅ Survives minority node failures
- ✅ Clock drift compensation
- ✅ Automatic permit expiration
- ✅ No single point of failure

**Potential Issues**:
- ⚠️ Higher latency
- ⚠️ More network overhead
- ⚠️ `availablePermits()` not accurate
- ⚠️ No permit counting mechanism
- ⚠️ Polling-based (no notifications)

**Consistency Model**:
```
Permit acquired if:
  - Quorum of nodes have the permit key
  - Validity time > 0
  - No clock drift issues
```

### Redisson

**Safety Guarantees**:
- ✅ Atomic operations (Lua scripts)
- ✅ Accurate permit counting
- ✅ Efficient pub/sub notifications
- ✅ Async/reactive support
- ✅ Low latency

**Potential Issues**:
- ⚠️ Single point of failure (single instance)
- ⚠️ No quorum mechanism
- ⚠️ No automatic permit expiration
- ⚠️ Permits persist indefinitely
- ⚠️ Thundering herd on notification

**Consistency Model**:
```
Permit acquired if:
  - Counter >= requested permits
  - Atomic decrement succeeds
  - No distributed consistency
```

## Use Case Comparison

### redlock4j RedlockSemaphore

**Best For**:
- Distributed systems requiring quorum-based safety
- Rate limiting with strong consistency
- Scenarios where permit expiration is critical
- Multi-master Redis setups
- Fault-tolerant resource pooling

**Example Scenarios**:
```java
// API rate limiting with fault tolerance
RedlockSemaphore apiLimiter = new RedlockSemaphore(
    "api:external:rate-limit", 100, redisDrivers, config
);

// Database connection pool with auto-expiration
RedlockSemaphore dbPool = new RedlockSemaphore(
    "db:connection:pool", 50, redisDrivers, config
);

// Distributed job throttling
RedlockSemaphore jobThrottle = new RedlockSemaphore(
    "jobs:concurrent-limit", 10, redisDrivers, config
);
```

### Redisson RedissonSemaphore

**Best For**:
- Single Redis instance deployments
- High-throughput rate limiting
- Scenarios requiring accurate permit counting
- Applications needing async/reactive APIs
- Dynamic permit management

**Example Scenarios**:
```java
// High-throughput API rate limiting
RSemaphore apiLimiter = redisson.getSemaphore("api:rate-limit");
apiLimiter.trySetPermits(1000);

// Resource pool with dynamic sizing
RSemaphore resourcePool = redisson.getSemaphore("resource:pool");
resourcePool.addPermits(50); // Can adjust at runtime

// Async rate limiting
RSemaphore asyncLimiter = redisson.getSemaphore("async:limiter");
asyncLimiter.trySetPermits(100);
RFuture<Boolean> future = asyncLimiter.tryAcquireAsync(5, TimeUnit.SECONDS);
```

## Complexity Analysis

### redlock4j

**Code Complexity**: ~370 lines

**Pros**:
- ✅ Quorum-based safety
- ✅ Automatic permit expiration
- ✅ Fault-tolerant
- ✅ Clock drift compensation
- ✅ Thread-local state tracking

**Cons**:
- ❌ Higher latency
- ❌ More Redis operations
- ❌ No accurate permit counting
- ❌ No permit management operations
- ❌ Polling-based waiting

### Redisson

**Code Complexity**: ~600 lines (with async support)

**Pros**:
- ✅ Low latency
- ✅ Atomic operations
- ✅ Accurate permit counting
- ✅ Pub/sub notifications
- ✅ Async/reactive support
- ✅ Rich API (drain, add, set permits)

**Cons**:
- ❌ Single point of failure
- ❌ No quorum mechanism
- ❌ No automatic expiration
- ❌ Permits persist indefinitely
- ❌ More complex implementation

## Feature Comparison Table

| Feature | redlock4j | Redisson |
|---------|-----------|----------|
| **Data Model** | Individual permit keys | Single counter |
| **Quorum** | Yes (per permit) | No |
| **Fault Tolerance** | Survives minority failures | Single point of failure |
| **Permit Expiration** | Automatic (TTL) | Manual (optional) |
| **Permit Counting** | Not accurate | Accurate (O(1)) |
| **Waiting Mechanism** | Polling | Pub/sub |
| **Fairness** | Non-fair | Non-fair |
| **Async Support** | No | Yes |
| **Reactive Support** | No | Yes |
| **Initialization** | Implicit | Explicit |
| **Permit Management** | Limited | Rich (add/drain/set) |
| **Performance** | O(N×M) | O(1) |
| **Latency** | Higher | Lower |
| **Network Overhead** | High | Low |
| **Clock Drift** | Compensated | Not applicable |

## Recommendations

### Choose redlock4j RedlockSemaphore when:

- ✅ Need quorum-based distributed consistency
- ✅ Require fault tolerance (multi-master)
- ✅ Automatic permit expiration is critical
- ✅ Can tolerate higher latency
- ✅ Prefer simpler initialization

### Choose Redisson RedissonSemaphore when:

- ✅ Single Redis instance is acceptable
- ✅ Need high throughput / low latency
- ✅ Require accurate permit counting
- ✅ Need async/reactive APIs
- ✅ Want dynamic permit management
- ✅ Efficient waiting (pub/sub) is important

### For Fair Semaphores:

- Use Redisson's `RedissonPermitExpirableSemaphore` for FIFO ordering
- redlock4j doesn't currently provide fair semaphore

## Migration Considerations

### From Redisson to redlock4j

```java
// Before (Redisson)
RSemaphore semaphore = redisson.getSemaphore("api-limiter");
semaphore.trySetPermits(5);
if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
    try {
        // work
    } finally {
        semaphore.release();
    }
}

// After (redlock4j)
RedlockSemaphore semaphore = new RedlockSemaphore(
    "api-limiter", 5, redisDrivers, config
);
if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
    try {
        // work
    } finally {
        semaphore.release();
    }
}
```

**Benefits**:
- Quorum-based safety
- Fault tolerance
- Automatic expiration

**Considerations**:
- Higher latency
- No accurate permit counting
- No dynamic permit management

### From redlock4j to Redisson

```java
// Before (redlock4j)
RedlockSemaphore semaphore = new RedlockSemaphore(
    "api-limiter", 5, redisDrivers, config
);

// After (Redisson)
RSemaphore semaphore = redisson.getSemaphore("api-limiter");
semaphore.trySetPermits(5);
```

**Benefits**:
- Lower latency
- Accurate permit counting
- Async/reactive support
- Dynamic permit management

**Considerations**:
- Single point of failure
- Must initialize explicitly
- No automatic expiration

## Conclusion

Both implementations serve different purposes:

**redlock4j RedlockSemaphore**:
- Designed for distributed consistency with quorum-based safety
- Individual permit keys with automatic expiration
- Higher latency but fault-tolerant
- Simpler initialization, limited management
- Best for multi-master setups requiring strong consistency

**Redisson RedissonSemaphore**:
- Designed for high-performance single-instance deployments
- Atomic counter with pub/sub notifications
- Lower latency but single point of failure
- Rich API with dynamic permit management
- Best for high-throughput scenarios with single Redis instance

Choose based on your specific requirements:
- **Distributed consistency & fault tolerance** → redlock4j RedlockSemaphore
- **High throughput & low latency** → Redisson RedissonSemaphore
- **Fair ordering (FIFO)** → Redisson RedissonPermitExpirableSemaphore

**Flow**:
1. Generate unique permit ID for each permit
2. For each permit, try `SET NX` on all nodes
3. Check if quorum achieved for each permit
4. If any permit fails quorum, rollback all
5. Validate total acquisition time

**Redis Operations** (for N permits on M nodes):
- N × M `SET NX` operations
- Rollback: up to N × M `DELETE` operations


