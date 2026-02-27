# ReadWriteLock Implementation Comparison: redlock4j vs Redisson

This document provides a detailed technical comparison of the ReadWriteLock implementations in redlock4j and Redisson.

## Overview

Both libraries implement distributed read-write locks to allow multiple concurrent readers or a single exclusive writer, but they use different data structures and synchronization mechanisms.

## Purpose & Use Case

### redlock4j RedlockReadWriteLock

**Purpose**: Distributed read-write lock with quorum-based safety guarantees

**Use Case**: Scenarios requiring strong consistency for read-heavy workloads

```java
RedlockReadWriteLock rwLock = new RedlockReadWriteLock(
    "resource", redisDrivers, config
);

// Multiple readers can acquire simultaneously
rwLock.readLock().lock();
try {
    readData();
} finally {
    rwLock.readLock().unlock();
}

// Writer has exclusive access
rwLock.writeLock().lock();
try {
    writeData();
} finally {
    rwLock.writeLock().unlock();
}
```

### Redisson RedissonReadWriteLock

**Purpose**: Distributed read-write lock with pub/sub notifications

**Use Case**: High-performance read-write scenarios with single Redis instance

```java
RReadWriteLock rwLock = redisson.getReadWriteLock("resource");

// Read lock
rwLock.readLock().lock();
try {
    readData();
} finally {
    rwLock.readLock().unlock();
}

// Write lock
rwLock.writeLock().lock();
try {
    writeData();
} finally {
    rwLock.writeLock().unlock();
}
```

## Architecture & Data Model

### redlock4j

**Design**: Counter-based with separate write lock

**Data Structure**:
```
{resourceKey}:readers = {count}                    (reader counter)
{resourceKey}:readers:{lockValue1} = "1"           (individual reader tracking)
{resourceKey}:readers:{lockValue2} = "1"           (individual reader tracking)
{resourceKey}:write = {lockValue}                  (exclusive write lock)
```

**Key Characteristics**:
- Reader count tracked via `INCR`/`DECR`
- Individual reader keys for tracking
- Write lock uses standard Redlock
- Quorum-based for both read and write
- Thread-local state for reentrancy

**Architecture**:
```
RedlockReadWriteLock
  ├─ ReadLock
  │   ├─ readCountKey ({key}:readers)
  │   ├─ writeLockKey ({key}:write)
  │   ├─ ThreadLocal<LockState>
  │   └─ Quorum-based INCR/DECR
  └─ WriteLock
      ├─ Redlock (for write lock)
      ├─ readCountKey ({key}:readers)
      └─ Polling for reader count
```

### Redisson

**Design**: Hash-based with mode tracking

**Data Structure**:
```
{resourceKey} = {
  "mode": "read" or "write",
  "{threadId1}": {holdCount},
  "{threadId2}": {holdCount},
  ...
}
redisson_rwlock:{resourceKey} = (pub/sub channel)
```

**Key Characteristics**:
- Single hash stores all lock state
- Mode field tracks read/write state
- Thread IDs as hash fields
- Lua scripts for atomicity
- Pub/sub for notifications

**Architecture**:
```
RedissonReadWriteLock
  ├─ RedissonReadLock
  │   ├─ Lua scripts for acquisition
  │   ├─ Hash-based state
  │   └─ Pub/sub notifications
  └─ RedissonWriteLock
      ├─ Lua scripts for acquisition
      ├─ Hash-based state
      └─ Pub/sub notifications
```

## Read Lock Acquisition

### redlock4j

**Algorithm**: Check write lock, then increment reader count

```java
public boolean tryLock(long time, TimeUnit unit) {
    // 1. Check reentrancy
    LockState currentState = lockState.get();
    if (currentState != null && currentState.isValid()) {
        currentState.incrementHoldCount();
        return true;
    }

    // 2. Retry loop


### Redisson

**Algorithm**: Lua script with hash-based state management

```lua
-- tryLockInnerAsync (simplified)
local mode = redis.call('hget', KEYS[1], 'mode');

-- If no lock or already in read mode
if (mode == false) then
    redis.call('hset', KEYS[1], 'mode', 'read');
    redis.call('hset', KEYS[1], ARGV[2], 1);
    redis.call('pexpire', KEYS[1], ARGV[1]);
    return nil;
end;

if (mode == 'read') or (mode == 'write' and redis.call('hexists', KEYS[1], ARGV[2]) == 1) then
    local ind = redis.call('hincrby', KEYS[1], ARGV[2], 1);
    local remainTime = redis.call('pttl', KEYS[1]);
    redis.call('pexpire', KEYS[1], math.max(remainTime, ARGV[1]));
    return nil;
end;

-- Write lock held by another thread
return redis.call('pttl', KEYS[1]);
```

**Flow**:
1. Get current mode from hash
2. If no lock: set mode='read', add thread, set TTL
3. If read mode: increment thread's hold count
4. If write mode by same thread: allow (lock downgrade)
5. If write mode by other thread: return TTL

**Redis Operations**:
- 1 Lua script execution (atomic)

## Write Lock Acquisition

### redlock4j

**Algorithm**: Wait for readers, then acquire exclusive lock

```java
public boolean tryLock(long time, TimeUnit unit) {
    long timeoutMs = unit.toMillis(time);
    long startTime = System.currentTimeMillis();

    // 1. Wait for readers to finish
    while (hasActiveReaders()) {
        if (timeoutExceeded(startTime, timeoutMs)) {
            return false;
        }
        Thread.sleep(retryDelayMs);
    }

    // 2. Acquire write lock using standard Redlock
    long remainingTime = timeoutMs - elapsed;
    return underlyingLock.tryLock(remainingTime, TimeUnit.MILLISECONDS);
}

private boolean hasActiveReaders() {
    int nodesWithoutReaders = 0;

    for (RedisDriver driver : redisDrivers) {
        String countStr = driver.get(readCountKey);
        if (countStr == null || Long.parseLong(countStr) <= 0) {
            nodesWithoutReaders++;
        }
    }

    // Quorum of nodes must have no readers
    return nodesWithoutReaders < quorum;
}
```

**Flow**:
1. Poll reader count on all nodes
2. Wait until quorum shows no readers
3. Acquire exclusive lock via Redlock
4. Check timeout throughout

**Redis Operations** (M nodes):
- N × M `GET` (polling reader count)
- M × `SET NX` (Redlock acquisition)

### Redisson

**Algorithm**: Lua script with mode transition

```lua
-- tryLockInnerAsync (simplified)
local mode = redis.call('hget', KEYS[1], 'mode');

-- No lock exists
if (mode == false) then
    redis.call('hset', KEYS[1], 'mode', 'write');
    redis.call('hset', KEYS[1], ARGV[2], 1);
    redis.call('pexpire', KEYS[1], ARGV[1]);
    return nil;
end;

-- Same thread already holds write lock (reentrant)
if (mode == 'write') then
    if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then
        redis.call('hincrby', KEYS[1], ARGV[2], 1);
        redis.call('pexpire', KEYS[1], ARGV[1]);
        return nil;
    end;
end;

-- Same thread holds read lock (lock upgrade)
if (mode == 'read') then
    local ind = redis.call('hget', KEYS[1], ARGV[2]);
    if (ind ~= false) then
        -- Check if this is the only reader
        if (redis.call('hlen', KEYS[1]) == 2) then -- mode + this thread
            redis.call('hset', KEYS[1], 'mode', 'write');
            return nil;
        end;
    end;
end;

-- Lock held by others
return redis.call('pttl', KEYS[1]);
```

**Flow**:
1. Get current mode
2. If no lock: set mode='write', add thread
3. If write mode by same thread: increment (reentrant)
4. If read mode by same thread only: upgrade to write
5. If held by others: return TTL

**Redis Operations**:
- 1 Lua script execution (atomic)

## Read Lock Release

### redlock4j

**Algorithm**: Decrement reader count

```java
public void unlock() {
    LockState state = lockState.get();

    // Handle reentrancy
    int remainingHolds = state.decrementHoldCount();
    if (remainingHolds > 0) {
        return;
    }

    // Decrement reader count on all nodes
    decrementReaderCount(state.lockValue);
    lockState.remove();
}

private void decrementReaderCount(String lockValue) {
    for (RedisDriver driver : redisDrivers) {
        // Decrement counter
        long count = driver.decr(readCountKey);

        // Delete individual reader key
        driver.del(readCountKey + ":" + lockValue);

        // Clean up counter if zero
        if (count <= 0) {
            driver.del(readCountKey);
        }
    }
}
```

**Characteristics**:
- DECR on all nodes
- Delete individual reader key
- Clean up counter when zero
- No notification to waiting writers

**Redis Operations** (M nodes):
- M × `DECR`
- M × `DEL` (individual key)
- M × `DEL` (counter, if zero)

### Redisson

**Algorithm**: Lua script with notification

```lua
-- unlockInnerAsync (simplified)
local mode = redis.call('hget', KEYS[1], 'mode');
if (mode == false) then
    return 1; -- Already unlocked
end;

local lockExists = redis.call('hexists', KEYS[1], ARGV[2]);
if (lockExists == 0) then
    return nil; -- Not held by this thread
end;

local counter = redis.call('hincrby', KEYS[1], ARGV[2], -1);
if (counter > 0) then
    redis.call('pexpire', KEYS[1], ARGV[1]);
    return 0; -- Still held (reentrant)
end;

redis.call('hdel', KEYS[1], ARGV[2]);
if (redis.call('hlen', KEYS[1]) > 1) then
    -- Other readers still exist
    redis.call('pexpire', KEYS[1], ARGV[1]);
    return 0;
end;

-- Last reader, delete lock and notify
redis.call('del', KEYS[1]);
redis.call('publish', KEYS[2], ARGV[1]);
return 1;
```

**Characteristics**:
- Atomic decrement in hash
- Delete thread field when zero
- Publish notification when last reader
- Waiting writers wake up immediately

**Redis Operations**:
- 1 Lua script execution
- 1 `PUBLISH` (if last reader)

## Write Lock Release

### redlock4j

**Algorithm**: Standard Redlock unlock

```java
public void unlock() {
    underlyingLock.unlock(); // Delegates to Redlock
}
```

**Characteristics**:
- Uses Redlock unlock mechanism
- Delete if value matches
- No notification to waiting readers/writers

**Redis Operations** (M nodes):
- M × Lua script (delete if matches)

### Redisson

**Algorithm**: Lua script with notification

```lua
-- Similar to read unlock but for write mode
-- Decrements hold count, deletes when zero, publishes notification
```

**Characteristics**:
- Atomic decrement in hash
- Delete lock when zero
- Publish notification to all waiters
- Immediate wake-up

**Redis Operations**:
- 1 Lua script execution
- 1 `PUBLISH`


## Lock Upgrade/Downgrade

### redlock4j

**Lock Upgrade** (Read → Write): Not supported

**Lock Downgrade** (Write → Read): Not supported

**Characteristics**:
- Must release read lock before acquiring write lock
- Must release write lock before acquiring read lock
- No automatic conversion
- Prevents potential deadlocks

### Redisson

**Lock Upgrade** (Read → Write): Supported (single reader only)

```lua
-- If this thread is the only reader, can upgrade to write
if (mode == 'read') then
    if (redis.call('hlen', KEYS[1]) == 2) then -- mode + this thread
        redis.call('hset', KEYS[1], 'mode', 'write');
        return nil;
    end;
end;
```

**Lock Downgrade** (Write → Read): Supported

```lua
-- If thread holds write lock, can acquire read lock (downgrade)
if (mode == 'write' and redis.call('hexists', KEYS[1], ARGV[2]) == 1) then
    local ind = redis.call('hincrby', KEYS[1], ARGV[2], 1);
    return nil;
end;
```

**Characteristics**:
- Automatic lock downgrade (write → read)
- Lock upgrade only if sole reader
- Prevents deadlock from multiple readers upgrading
- More flexible but more complex

## Fairness & Ordering

### redlock4j

**Fairness**: Non-fair (no ordering guarantees)

**Characteristics**:
- Readers and writers compete equally
- No FIFO queue
- Retry-based acquisition
- Potential writer starvation if many readers

**Example**:
```
Reader 1: acquire → success
Reader 2: acquire → success
Writer 1: tryLock → blocks (waiting for readers)
Reader 3: acquire → success (can acquire while writer waits)
Writer 1: still waiting...
```

### Redisson

**Fairness**: Non-fair by default

**Characteristics**:
- No ordering between readers and writers
- Pub/sub wakes all waiters
- Race to acquire after notification
- Also has `RedissonFairReadWriteLock` for FIFO ordering

**Note**: Redisson provides `RedissonFairReadWriteLock` for fair ordering with FIFO guarantees.

## Performance Comparison

### redlock4j

**Read Lock Acquisition** (M nodes):
- M × `GET` (check write lock)
- M × `INCR` (increment counter)
- M × `SETEX` (individual reader key)
- Total: 3M operations

**Write Lock Acquisition** (M nodes):
- N × M `GET` (poll reader count)
- M × `SET NX` (Redlock)
- Total: (N+1)M operations

**Complexity**: O(M) for reads, O(N×M) for writes

**Latency**:
- Higher due to quorum requirement
- Polling overhead for writers
- No pub/sub overhead

### Redisson

**Read Lock Acquisition**:
- 1 Lua script execution
- If blocked: 1 pub/sub subscription
- Total: 1-2 operations

**Write Lock Acquisition**:
- 1 Lua script execution
- If blocked: 1 pub/sub subscription
- Total: 1-2 operations

**Complexity**: O(1) per operation

**Latency**:
- Lower for single instance
- Single round trip
- Pub/sub notification overhead

## Safety & Correctness

### redlock4j

**Safety Guarantees**:
- ✅ Quorum-based consistency
- ✅ Survives minority node failures
- ✅ Multiple readers guaranteed
- ✅ Exclusive writer guaranteed
- ✅ No single point of failure

**Potential Issues**:
- ⚠️ Higher latency
- ⚠️ More network overhead
- ⚠️ Polling-based (no notifications)
- ⚠️ Potential writer starvation
- ⚠️ No lock upgrade/downgrade

**Consistency Model**:
```
Read lock acquired if:
  - Quorum shows no write lock
  - Reader count incremented on quorum

Write lock acquired if:
  - Quorum shows no readers
  - Exclusive lock acquired on quorum
```

### Redisson

**Safety Guarantees**:
- ✅ Atomic operations (Lua scripts)
- ✅ Multiple readers guaranteed
- ✅ Exclusive writer guaranteed
- ✅ Lock upgrade/downgrade support
- ✅ Pub/sub notifications
- ✅ Async/reactive support

**Potential Issues**:
- ⚠️ Single point of failure
- ⚠️ No quorum mechanism
- ⚠️ Potential writer starvation (non-fair)
- ⚠️ More complex Lua scripts

**Consistency Model**:
```
Lock acquired if:
  - Mode allows acquisition
  - Atomic state transition succeeds
  - No distributed consistency
```

## Use Case Comparison

### redlock4j RedlockReadWriteLock

**Best For**:
- Distributed systems requiring quorum-based safety
- Read-heavy workloads with strong consistency
- Multi-master Redis setups
- Fault-tolerant read-write scenarios
- Can tolerate higher latency

**Example Scenarios**:
```java
// Distributed cache with strong consistency
RedlockReadWriteLock cacheLock = new RedlockReadWriteLock(
    "cache:users", redisDrivers, config
);

// Configuration management
RedlockReadWriteLock configLock = new RedlockReadWriteLock(
    "config:app", redisDrivers, config
);
```

### Redisson RedissonReadWriteLock

**Best For**:
- Single Redis instance deployments
- High-throughput read-write scenarios
- Applications needing async/reactive APIs
- Lock upgrade/downgrade requirements
- Low-latency requirements

**Example Scenarios**:
```java
// High-performance cache
RReadWriteLock cacheLock = redisson.getReadWriteLock("cache:users");

// Document editing with lock downgrade
RReadWriteLock docLock = redisson.getReadWriteLock("doc:123");
docLock.writeLock().lock();
try {
    editDocument();
    docLock.readLock().lock(); // Downgrade
    docLock.writeLock().unlock();
    try {
        readDocument();
    } finally {
        docLock.readLock().unlock();
    }
} finally {
    if (docLock.writeLock().isHeldByCurrentThread()) {
        docLock.writeLock().unlock();
    }
}
```

## Feature Comparison Table

| Feature | redlock4j | Redisson |
|---------|-----------|----------|
| **Data Model** | Counter + individual keys | Hash with mode field |
| **Quorum** | Yes | No |
| **Fault Tolerance** | Survives minority failures | Single point of failure |
| **Lock Upgrade** | No | Yes (single reader only) |
| **Lock Downgrade** | No | Yes |
| **Waiting Mechanism** | Polling | Pub/sub |
| **Fairness** | Non-fair | Non-fair (fair variant available) |
| **Async Support** | No | Yes |
| **Reactive Support** | No | Yes |
| **Performance** | O(M) reads, O(N×M) writes | O(1) |
| **Latency** | Higher | Lower |
| **Network Overhead** | High | Low |
| **Atomicity** | Quorum-based | Lua scripts |

## Recommendations

### Choose redlock4j RedlockReadWriteLock when:

- ✅ Need quorum-based distributed consistency
- ✅ Require fault tolerance (multi-master)
- ✅ Read-heavy workloads with strong consistency
- ✅ Can tolerate higher latency
- ✅ Don't need lock upgrade/downgrade

### Choose Redisson RedissonReadWriteLock when:

- ✅ Single Redis instance is acceptable
- ✅ Need high throughput / low latency
- ✅ Require lock upgrade/downgrade
- ✅ Need async/reactive APIs
- ✅ Want pub/sub notifications
- ✅ Need fair ordering (use RedissonFairReadWriteLock)

## Conclusion

Both implementations provide distributed read-write locks with different trade-offs:

**redlock4j RedlockReadWriteLock**:
- Counter-based with quorum safety
- Higher latency but fault-tolerant
- Polling-based waiting
- No lock conversion support
- Best for multi-master setups requiring strong consistency

**Redisson RedissonReadWriteLock**:
- Hash-based with atomic Lua scripts
- Lower latency but single point of failure
- Pub/sub notifications
- Lock upgrade/downgrade support
- Best for high-throughput single-instance deployments

Choose based on your specific requirements:
- **Distributed consistency & fault tolerance** → redlock4j
- **High throughput & low latency** → Redisson
- **Fair ordering** → Redisson RedissonFairReadWriteLock



