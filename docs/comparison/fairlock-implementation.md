# FairLock Implementation Comparison: redlock4j vs Redisson

This document provides a detailed technical comparison of the FairLock implementations in redlock4j and Redisson.

## Overview

Both libraries implement fair locks to ensure FIFO (First-In-First-Out) ordering for lock acquisition, but they use different approaches and data structures.

## Architecture & Data Structures

### redlock4j

- **Data Structure**: Redis Sorted Sets (ZSET) exclusively
- **Keys Used**:
  - Lock key: `{lockKey}`
  - Queue key: `{lockKey}:queue`
- **Queue Management**: Tokens stored with timestamps as scores
- **Approach**: Simpler two-key design

### Redisson

- **Data Structures**: Redis List (LIST) + Sorted Set (ZSET)
- **Keys Used**:
  - Lock key: `{lockName}`
  - Queue key: `redisson_lock_queue:{lockName}`
  - Timeout key: `redisson_lock_timeout:{lockName}`
- **Queue Management**: List for ordering, sorted set for timeout tracking
- **Approach**: Three-key design with separate timeout management

## Queue Management Operations

### redlock4j

```java
// Add to queue
driver.zAdd(queueKey, timestamp, token)

// Check position
List<String> first = driver.zRange(queueKey, 0, 0)
boolean atFront = token.equals(first.get(0))

// Remove from queue
driver.zRem(queueKey, token)

// Cleanup expired
driver.zRemRangeByScore(queueKey, 0, expirationThreshold)
```

**Characteristics**:
- Position determined by timestamp score
- Natural ordering by insertion time
- Single operation for position check

### Redisson

```lua
-- Add to queue
redis.call('rpush', KEYS[2], ARGV[2])
redis.call('zadd', KEYS[3], timeout, ARGV[2])

-- Check position
local firstThreadId = redis.call('lindex', KEYS[2], 0)

-- Remove from queue
redis.call('lpop', KEYS[2])
redis.call('zrem', KEYS[3], ARGV[2])
```

**Characteristics**:
- Position determined by list insertion order
- Separate timeout tracking in sorted set
- Two operations required for queue management

## Fairness Guarantee

### redlock4j

- **Ordering**: Based on timestamp when added to queue
- **Quorum**: Requires majority agreement on queue position
- **Verification**: `isAtFrontOfQueue()` checks across multiple nodes
- **Clock Dependency**: Relies on reasonably synchronized clocks

```java
int votesForFront = 0;
for (RedisDriver driver : redisDrivers) {
    List<String> firstElements = driver.zRange(queueKey, 0, 0);
    if (!firstElements.isEmpty() && token.equals(firstElements.get(0))) {
        votesForFront++;
    }
}
return votesForFront >= config.getQuorum();
```

### Redisson

- **Ordering**: Based on list insertion order (FIFO)
- **Stale Cleanup**: Removes expired threads before each operation
- **Timeout Calculation**: Dynamic based on queue position
- **Clock Dependency**: Uses timeouts but less sensitive to clock skew

```lua
-- Cleanup stale threads first
while true do
    local firstThreadId = redis.call('lindex', KEYS[2], 0)
    if firstThreadId == false then break end
    local timeout = redis.call('zscore', KEYS[3], firstThreadId)
    if timeout ~= false and tonumber(timeout) <= tonumber(ARGV[4]) then
        redis.call('zrem', KEYS[3], firstThreadId)
        redis.call('lpop', KEYS[2])
    else break end
end
```

## Stale Entry Cleanup

### redlock4j

**Strategy**: Periodic cleanup during queue operations

```java
private void addToQueue(String token, long timestamp) {
    // Add to queue
    for (RedisDriver driver : redisDrivers) {
        driver.zAdd(queueKey, timestamp, token);
    }

    // Cleanup expired entries
    long expirationThreshold = System.currentTimeMillis()
        - config.getDefaultLockTimeoutMs() * 2;
    cleanupExpiredQueueEntries(expirationThreshold);
}
```

**Characteristics**:
- Cleanup triggered on `addToQueue()`
- Removes entries older than 2x lock timeout
- Separate cleanup operation

### Redisson

**Strategy**: Cleanup before every lock operation

```lua
-- Embedded in every Lua script
while true do
    local firstThreadId = redis.call('lindex', KEYS[2], 0)
    if firstThreadId == false then break end
    local timeout = redis.call('zscore', KEYS[3], firstThreadId)
    if timeout ~= false and tonumber(timeout) <= currentTime then
        redis.call('zrem', KEYS[3], firstThreadId)
        redis.call('lpop', KEYS[2])
    else break end
end
```

**Characteristics**:
- State stored in Redis
- Requires Redis call for reentrant check
- Survives JVM restart
- Consistent across instances

## Lock Acquisition Flow

### redlock4j

```java
public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
    // 1. Check for reentrancy (thread-local)
    LockState currentState = lockState.get();
    if (currentState != null && currentState.isValid()) {
        currentState.incrementHoldCount();
        return true;
    }

    // 2. Add to queue with timestamp
    String queueToken = generateToken();
    long timestamp = System.currentTimeMillis();
    addToQueue(queueToken, timestamp);

    // 3. Retry loop
    for (int attempt = 0; attempt <= maxRetryAttempts; attempt++) {
        // 4. Check if at front (quorum-based)
        if (isAtFrontOfQueue(queueToken)) {
            // 5. Try standard Redlock acquisition
            LockResult result = attemptLock();
            if (result.isAcquired()) {
                lockState.set(new LockState(...));
                return true;
            }
        }

        // 6. Check timeout
        if (timeoutExceeded) {
            removeFromQueue(queueToken);
            break;
        }

        // 7. Wait and retry
        Thread.sleep(retryDelayMs);
    }

    removeFromQueue(queueToken);
    return false;
}
```

**Flow**:
1. Check thread-local reentrancy
2. Add to queue with current timestamp
3. Poll until at front of queue (quorum check)
4. Attempt standard Redlock acquisition
5. Remove from queue on success/failure

### Redisson

```lua
-- 1. Clean stale threads
while true do
    -- Remove expired threads from front
end

-- 2. Check if lock can be acquired
if (redis.call('exists', KEYS[1]) == 0)
    and ((redis.call('exists', KEYS[2]) == 0)
        or (redis.call('lindex', KEYS[2], 0) == ARGV[2])) then

    -- 3. Remove from queue
    redis.call('lpop', KEYS[2])
    redis.call('zrem', KEYS[3], ARGV[2])

    -- 4. Decrease timeouts for remaining waiters
    local keys = redis.call('zrange', KEYS[3], 0, -1)
    for i = 1, #keys, 1 do
        redis.call('zincrby', KEYS[3], -tonumber(ARGV[3]), keys[i])
    end

    -- 5. Acquire lock
    redis.call('hset', KEYS[1], ARGV[2], 1)
    redis.call('pexpire', KEYS[1], ARGV[1])
    return nil
end

-- 6. Check for reentrancy
if redis.call('hexists', KEYS[1], ARGV[2]) == 1 then
    redis.call('hincrby', KEYS[1], ARGV[2], 1)
    return nil
end

-- 7. Add to queue if not present
local timeout = redis.call('zscore', KEYS[3], ARGV[2])
if timeout == false then
    -- Calculate timeout based on queue position
    local lastThreadId = redis.call('lindex', KEYS[2], -1)
    local ttl = redis.call('pttl', KEYS[1])
    local timeout = ttl + threadWaitTime + currentTime
    redis.call('zadd', KEYS[3], timeout, ARGV[2])
    redis.call('rpush', KEYS[2], ARGV[2])
end
return ttl
```

**Flow**:
1. Clean stale threads from queue
2. Check if lock is free AND (queue empty OR at front)
3. Remove self from queue
4. Adjust timeouts for remaining threads
5. Acquire lock or return TTL

## Timeout Handling

### redlock4j

**Strategy**: Fixed timeout with retry

```java
long timeoutMs = unit.toMillis(time);
long startTime = System.currentTimeMillis();

for (int attempt = 0; attempt <= maxRetryAttempts; attempt++) {
    // Try to acquire

    if (timeoutMs > 0 && (System.currentTimeMillis() - startTime) >= timeoutMs) {
        removeFromQueue(queueToken);
        break;
    }

    Thread.sleep(retryDelayMs);
}
```

**Characteristics**:
- Simple timeout check
- Fixed retry delay
- Client-side timeout management
- No timeout estimation

### Redisson

**Strategy**: Dynamic timeout calculation

```lua
-- Calculate timeout based on queue position
local lastThreadId = redis.call('lindex', KEYS[2], -1)
local ttl
if lastThreadId ~= false and lastThreadId ~= ARGV[2] then
    ttl = tonumber(redis.call('zscore', KEYS[3], lastThreadId)) - currentTime
else
    ttl = redis.call('pttl', KEYS[1])
end
local timeout = ttl + threadWaitTime + currentTime
```

**Characteristics**:
- Returns estimated wait time (TTL)
- Timeout = previous thread timeout + threadWaitTime
- Server-side calculation
- More accurate wait estimation
- Default threadWaitTime: 5 seconds

## Unlock & Notification

### redlock4j

**Strategy**: Simple unlock without notification

```java
public void unlock() {
    LockState state = lockState.get();

    // Handle reentrancy
    int remainingHolds = state.decrementHoldCount();
    if (remainingHolds > 0) {
        return;
    }

    // Release lock
    releaseLock(state.lockValue);
    removeFromQueue(state.queueToken);
    lockState.remove();
}
```

**Characteristics**:
- No explicit notification to next waiter
- Next thread discovers via polling
- Simpler implementation
- Higher latency for next waiter

### Redisson

**Strategy**: Pub/Sub notification to next waiter

```lua
-- After releasing lock
redis.call('del', KEYS[1])
local nextThreadId = redis.call('lindex', KEYS[2], 0)
if nextThreadId ~= false then
    redis.call('publish', KEYS[4] .. ':' .. nextThreadId, ARGV[1])
end
```

**Characteristics**:
- Explicit wake-up via Redis pub/sub
- Next thread notified immediately
- Lower latency for next waiter
- More complex implementation
- Requires pub/sub subscription per thread

## Complexity Analysis

### redlock4j

**Lines of Code**: ~390 lines

**Pros**:
- ✅ Simpler to understand
- ✅ Single data structure (sorted set)
- ✅ Fewer Redis operations
- ✅ Less state to manage
- ✅ Timestamp-based ordering is intuitive

**Cons**:
- ❌ Clock skew sensitivity
- ❌ Polling-based (no notifications)
- ❌ Cleanup only on addToQueue
- ❌ Less sophisticated timeout handling

### Redisson

**Lines of Code**: ~350 lines (but denser Lua scripts)

**Pros**:
- ✅ Robust stale thread handling
- ✅ Better timeout estimation
- ✅ Explicit thread notification (pub/sub)
- ✅ Less clock-dependent (list ordering)
- ✅ Production-hardened

**Cons**:
- ❌ More complex implementation
- ❌ Two data structures to maintain
- ❌ More Redis operations per attempt
- ❌ Cleanup overhead on every operation
- ❌ Requires pub/sub infrastructure

## Performance Comparison

### redlock4j

**Lock Acquisition**:
- 1x `ZADD` per node (add to queue)
- 1x `ZRANGE` per node per attempt (check position)
- Nx `SET NX` per node (standard Redlock)
- 1x `ZREM` per node (remove from queue)

**Unlock**:
- 1x Lua script per node (delete if matches)
- 1x `ZREM` per node (remove from queue)

**Total**: Moderate Redis operations, polling overhead

### Redisson

**Lock Acquisition**:
- 1x Lua script (all operations atomic)
  - Stale cleanup (variable)
  - Queue check
  - Lock acquisition
  - Timeout updates

**Unlock**:
- 1x Lua script
  - Stale cleanup
  - Lock release
  - Pub/sub notification

**Total**: Fewer round trips, but heavier Lua scripts

## Edge Cases & Robustness

### redlock4j

**Clock Skew**:
- Timestamps used for ordering
- Significant clock skew could affect fairness
- Mitigated by clock drift factor in Redlock

**Stale Entries**:
- Cleaned up on `addToQueue()`
- Could accumulate between additions
- Threshold: 2x lock timeout

**Network Partitions**:
- Quorum-based queue position check
- Handles minority node failures
- Consistent with Redlock guarantees

### Redisson

**Clock Skew**:
- List ordering independent of clocks
- Timeouts use timestamps but less critical
- More resilient to clock issues

**Stale Entries**:
- Cleaned up on every operation
- Only from front of queue
- More consistent cleanup

**Network Partitions**:
- Single instance by default
- No quorum mechanism
- Less resilient than redlock4j

## Recommendations

### For redlock4j

**Consider Adopting**:
1. **Pub/Sub Notifications**: Reduce polling latency
2. **Dynamic Timeout Estimation**: Return TTL to caller
3. **More Frequent Cleanup**: Clean on every operation
4. **List-Based Ordering**: Reduce clock dependency

**Keep**:
1. Quorum-based queue position check
2. Thread-local state for performance
3. Simple two-key design
4. Standard Redlock integration

### For Production Use

**Choose redlock4j when**:
- True distributed locking is required
- Simplicity and maintainability matter
- Quorum-based safety is essential
- Minimal dependencies preferred

**Choose Redisson when**:
- Single instance is acceptable
- Need full Redis framework
- Pub/Sub infrastructure available
- Production-hardened solution needed

## Conclusion

Both implementations provide fair locking with different trade-offs:

- **redlock4j**: Simpler, quorum-based, timestamp-ordered, polling-based
- **Redisson**: Complex, single-instance, list-ordered, notification-based

The choice depends on your specific requirements for safety, performance, and operational complexity.

**Characteristics**:
- Cleanup on every lock attempt
- Only removes from front of queue
- Integrated into lock acquisition logic
- Higher overhead but more consistent

## Reentrancy Handling

### redlock4j

**Storage**: Thread-local state

```java
private final ThreadLocal<LockState> lockState = new ThreadLocal<>();

private static class LockState {
    final String lockValue;
    final String queueToken;
    final long acquisitionTime;
    final long validityTime;
    int holdCount;
}

// On reentrant lock
LockState currentState = lockState.get();
if (currentState != null && currentState.isValid()) {
    currentState.incrementHoldCount();
    return true;
}
```

**Characteristics**:
- State stored in JVM memory
- Fast access (no Redis call)
- Per-thread tracking
- Lost on JVM restart

### Redisson

**Storage**: Redis hash field

```lua
-- Check for reentrant lock
if redis.call('hexists', KEYS[1], ARGV[2]) == 1 then
    redis.call('hincrby', KEYS[1], ARGV[2], 1)
    redis.call('pexpire', KEYS[1], ARGV[1])
    return nil
end
```


