# MultiLock Implementation Comparison: redlock4j vs Redisson

This document provides a detailed technical comparison of the MultiLock implementations in redlock4j and Redisson.

## Overview

Both libraries implement multi-lock functionality to atomically acquire multiple locks, but they use fundamentally different approaches and have different design goals.

## Purpose & Use Case

### redlock4j MultiLock

**Purpose**: Atomic acquisition of multiple independent resources with deadlock prevention

**Use Case**: When you need to lock multiple resources simultaneously (e.g., transferring between multiple bank accounts)

```java
MultiLock multiLock = new MultiLock(
    Arrays.asList("account:1", "account:2", "account:3"),
    redisDrivers,
    config
);
multiLock.lock();
try {
    // All three accounts are now locked
    transferBetweenAccounts();
} finally {
    multiLock.unlock();
}
```

### Redisson RedissonMultiLock

**Purpose**: Group multiple RLock objects and manage them as a single lock

**Use Case**: When you have multiple independent locks (possibly from different Redisson instances) that need to be acquired together

```java
RLock lock1 = redisson1.getLock("lock1");
RLock lock2 = redisson2.getLock("lock2");
RLock lock3 = redisson3.getLock("lock3");

RedissonMultiLock multiLock = new RedissonMultiLock(lock1, lock2, lock3);
multiLock.lock();
try {
    // All locks acquired
} finally {
    multiLock.unlock();
}
```

## Architecture & Design Philosophy

### redlock4j

**Design**: Integrated Redlock implementation for multiple resources

**Key Characteristics**:
- Each resource is a separate key on the same Redis cluster
- Uses the same Redlock quorum mechanism for all resources
- Sorted keys to prevent deadlocks
- All-or-nothing acquisition with automatic rollback

**Architecture**:
```
MultiLock
  ├─ List<String> lockKeys (sorted)
  ├─ List<RedisDriver> redisDrivers (shared cluster)
  ├─ Quorum-based acquisition per resource
  └─ Thread-local state tracking
```

### Redisson

**Design**: Wrapper around multiple independent RLock objects

**Key Characteristics**:
- Each RLock can be from a different Redisson instance
- Each lock uses its own Redis connection/cluster
- No inherent deadlock prevention (no key sorting)
- Sequential acquisition with retry logic

**Architecture**:
```
RedissonMultiLock
  ├─ List<RLock> locks (order preserved)
  ├─ Each RLock has its own connection
  ├─ Sequential acquisition
  └─ Configurable failure tolerance
```

## Deadlock Prevention

### redlock4j

**Strategy**: Automatic key sorting

```java
// Constructor automatically sorts keys
this.lockKeys = lockKeys.stream()
    .distinct()
    .sorted()  // Lexicographic ordering
    .collect(Collectors.toList());
```

**Guarantee**: All threads acquire locks in the same order, preventing circular wait conditions

**Example**:
```java
// Thread 1: locks ["account:1", "account:2", "account:3"]
// Thread 2: locks ["account:3", "account:1", "account:2"]
// Both will acquire in order: account:1 → account:2 → account:3
```

### Redisson

**Strategy**: No automatic deadlock prevention

```java
// Locks are acquired in the order provided
public RedissonMultiLock(RLock... locks) {
    this.locks.addAll(Arrays.asList(locks));
}
```

**Risk**: Developer must ensure consistent ordering

**Example**:
```java
// Thread 1: new RedissonMultiLock(lock1, lock2, lock3)
// Thread 2: new RedissonMultiLock(lock3, lock1, lock2)
// DEADLOCK POSSIBLE if not careful!
```

## Lock Acquisition Algorithm

### redlock4j

**Algorithm**: Quorum-based atomic acquisition

```java
private MultiLockResult attemptMultiLock() {
    Map<String, String> lockValues = new HashMap<>();

    // 1. Generate unique values for each key
    for (String key : lockKeys) {
        lockValues.put(key, generateLockValue());
    }

    // 2. Try to acquire ALL locks on EACH Redis node
    int successfulNodes = 0;
    for (RedisDriver driver : redisDrivers) {
        if (acquireAllOnNode(driver, lockValues)) {
            successfulNodes++;
        }
    }

    // 3. Check quorum and validity
    boolean acquired = successfulNodes >= config.getQuorum()
                    && validityTime > 0;

    // 4. Rollback if failed
    if (!acquired) {
        releaseAllLocks(lockValues);
    }

    return new MultiLockResult(acquired, validityTime, lockValues, ...);
}
```

**Per-Node Acquisition**:
```java
private boolean acquireAllOnNode(RedisDriver driver, Map<String, String> lockValues) {
    List<String> acquiredKeys = new ArrayList<>();

    for (String key : lockKeys) {
        if (driver.setIfNotExists(key, lockValue, timeout)) {
            acquiredKeys.add(key);
        } else {
            // Failed - rollback this node
            rollbackOnNode(driver, lockValues, acquiredKeys);
            return false;
        }
    }
    return true;
}
```

**Flow**:
1. Generate unique lock values for all keys
2. For each Redis node:
   - Try to acquire ALL locks
   - If any fails, rollback that node
3. Check if quorum achieved
4. If not, release all acquired locks

### Redisson

**Algorithm**: Sequential acquisition with retry and failure tolerance

```java
public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) {
    long remainTime = unit.toMillis(waitTime);
    long lockWaitTime = calcLockWaitTime(remainTime);

    int failedLocksLimit = failedLocksLimit(); // Default: 0
    List<RLock> acquiredLocks = new ArrayList<>();

    // 1. Iterate through locks sequentially
    for (ListIterator<RLock> iterator = locks.listIterator(); iterator.hasNext();) {
        RLock lock = iterator.next();
        boolean lockAcquired;

        try {
            // 2. Try to acquire this lock
            long awaitTime = Math.min(lockWaitTime, remainTime);
            lockAcquired = lock.tryLock(awaitTime, newLeaseTime, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            lockAcquired = false;
        }

        if (lockAcquired) {
            acquiredLocks.add(lock);
        } else {
            // 3. Check if we can tolerate this failure
            if (locks.size() - acquiredLocks.size() == failedLocksLimit()) {
                break; // Acquired enough
            }

            // 4. If no tolerance, retry from beginning
            if (failedLocksLimit == 0) {
                unlockInner(acquiredLocks);
                if (waitTime <= 0) {
                    return false;
                }
                // Reset and retry
                acquiredLocks.clear();
                while (iterator.hasPrevious()) {
                    iterator.previous();
                }
            } else {
                failedLocksLimit--;
            }
        }

        // 5. Check timeout
        if (remainTime > 0) {
            remainTime -= elapsed;
            if (remainTime <= 0) {
                unlockInner(acquiredLocks);
                return false;
            }
        }
    }

    // 6. Set lease time on all acquired locks
    if (leaseTime > 0) {
        acquiredLocks.stream()
            .map(l -> (RedissonBaseLock) l)
            .map(l -> l.expireAsync(unit.toMillis(leaseTime), TimeUnit.MILLISECONDS))
            .forEach(f -> f.toCompletableFuture().join());
    }

    return true;
}
```

**Flow**:
1. Iterate through locks in provided order
2. Try to acquire each lock individually
3. If failure and no tolerance: unlock all and retry from start
4. If failure with tolerance: continue to next lock
5. Check timeout after each attempt
6. Set lease time on all acquired locks

## Failure Handling & Rollback

### redlock4j

**Strategy**: Per-node rollback with all-or-nothing semantics

```java
private void rollbackOnNode(RedisDriver driver, Map<String, String> lockValues,
                           List<String> acquiredKeys) {
    for (String key : acquiredKeys) {
        try {
            driver.deleteIfValueMatches(key, lockValues.get(key));
        } catch (Exception e) {
            logger.warn("Failed to rollback lock {} on {}", key, driver);
        }
    }
}
```

**Characteristics**:
- Immediate rollback on any failure within a node
- All-or-nothing per node
- Quorum check after all nodes attempted
- Global rollback if quorum not achieved

**Example**:
```
Node 1: account:1 ✓, account:2 ✓, account:3 ✓ → Success
Node 2: account:1 ✓, account:2 ✗ → Rollback account:1 on Node 2
Node 3: account:1 ✓, account:2 ✓, account:3 ✓ → Success

Result: 2/3 nodes succeeded
If quorum=2: SUCCESS
If quorum=3: FAIL → Rollback all nodes
```

### Redisson

**Strategy**: Retry from beginning on failure (default) or tolerance-based

```java
protected int failedLocksLimit() {
    return 0; // No tolerance by default
}
```

**Characteristics**:
- Default: unlock all and retry from start
- Can be overridden for failure tolerance
- No per-lock rollback
- Sequential retry logic

**Example** (default behavior):
```
Attempt 1: lock1 ✓, lock2 ✗ → Unlock lock1, retry
Attempt 2: lock1 ✓, lock2 ✓, lock3 ✓ → Success
```

**Example** (with tolerance in RedissonRedLock):
```
RedissonRedLock extends RedissonMultiLock {
    protected int failedLocksLimit() {
        return locks.size() - minLocksAmount(locks);
    }

    protected int minLocksAmount(List<RLock> locks) {
        return locks.size() / 2 + 1; // Quorum
    }
}

Attempt: lock1 ✓, lock2 ✗, lock3 ✓ → Success (2/3 acquired)
```

## Reentrancy Support

### redlock4j

**Implementation**: Thread-local state with hold count

```java
private final ThreadLocal<LockState> lockState = new ThreadLocal<>();

private static class LockState {
    final Map<String, String> lockValues; // All lock values
    final long acquisitionTime;
    final long validityTime;
    int holdCount;
}

public boolean tryLock(long time, TimeUnit unit) {
    // Check reentrancy
    LockState currentState = lockState.get();
    if (currentState != null && currentState.isValid()) {
        currentState.incrementHoldCount();
        return true;
    }
    // ... acquire logic
}

public void unlock() {
    LockState state = lockState.get();
    int remainingHolds = state.decrementHoldCount();
    if (remainingHolds > 0) {
        return; // Still held
    }
    // ... release logic
}
```

**Characteristics**:
- Single hold count for all locks together
- Fast (no Redis calls for reentrant acquisition)
- Thread-local storage
- All locks treated as atomic unit

### Redisson

**Implementation**: Delegates to individual RLock reentrancy

```java
// Each RLock handles its own reentrancy
public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) {
    for (RLock lock : locks) {
        // Each lock checks its own hold count in Redis
        lockAcquired = lock.tryLock(awaitTime, newLeaseTime, TimeUnit.MILLISECONDS);
    }
}

public void unlock() {
    locks.forEach(Lock::unlock);
}
```

**Characteristics**:
- Each lock maintains its own hold count
- Requires Redis calls for each lock
- Independent reentrancy per lock
- Locks can have different hold counts

## Timeout & Validity Calculation

### redlock4j

**Strategy**: Single validity time for all locks

```java
long startTime = System.currentTimeMillis();

// Acquire all locks...

long elapsedTime = System.currentTimeMillis() - startTime;
long driftTime = (long) (config.getDefaultLockTimeoutMs() * config.getClockDriftFactor()) + 2;
long validityTime = config.getDefaultLockTimeoutMs() - elapsedTime - driftTime;

boolean acquired = successfulNodes >= config.getQuorum() && validityTime > 0;
```

**Characteristics**:
- Single validity calculation for entire multi-lock
- Clock drift compensation
- All locks expire together
- Validity must be positive for success

### Redisson

**Strategy**: Individual timeout per lock with dynamic wait time

```java
long baseWaitTime = locks.size() * 1500; // 1.5s per lock

long waitTime;
if (leaseTime <= 0) {
    waitTime = baseWaitTime;
} else {
    waitTime = unit.toMillis(leaseTime);
    if (waitTime <= baseWaitTime) {
        waitTime = ThreadLocalRandom.current().nextLong(waitTime/2, waitTime);
    } else {
        waitTime = ThreadLocalRandom.current().nextLong(baseWaitTime, waitTime);
    }
}
```

**Characteristics**:
- Dynamic wait time based on number of locks
- Randomization to avoid thundering herd
- Each lock can have different lease time
- No global validity check

## Performance Comparison

### redlock4j

**Lock Acquisition**:
- For N resources on M nodes:
  - N × M `SET NX` operations (parallel per node)
  - Rollback: up to N × M `DELETE` operations
- Single round of attempts per retry
- All nodes contacted in parallel

**Complexity**: O(N × M) per attempt

**Example** (3 resources, 3 nodes):
```
Attempt 1:
  Node 1: SET account:1, SET account:2, SET account:3
  Node 2: SET account:1, SET account:2, SET account:3
  Node 3: SET account:1, SET account:2, SET account:3
Total: 9 operations (parallel)
```

### Redisson

**Lock Acquisition**:
- For N locks:
  - N sequential lock attempts
  - Each lock may involve multiple Redis operations
  - Retry from beginning on failure
- Sequential processing

**Complexity**: O(N × R) where R = retry attempts

**Example** (3 locks):
```
Attempt 1:
  lock1.tryLock() → Redis operations
  lock2.tryLock() → Redis operations
  lock2 fails → unlock lock1
Attempt 2:
  lock1.tryLock() → Redis operations
  lock2.tryLock() → Redis operations
  lock3.tryLock() → Redis operations
Total: Variable, sequential
```


## Use Case Differences

### redlock4j MultiLock

**Best For**:
- Locking multiple resources on the same Redis cluster
- Scenarios requiring strict deadlock prevention
- Atomic operations across multiple keys
- Distributed systems with quorum requirements

**Example Scenarios**:
```java
// Bank transfer between multiple accounts
MultiLock lock = new MultiLock(
    Arrays.asList("account:1", "account:2", "account:3"),
    redisDrivers, config
);

// Inventory management across warehouses
MultiLock lock = new MultiLock(
    Arrays.asList("warehouse:A:item:123", "warehouse:B:item:123"),
    redisDrivers, config
);
```

### Redisson RedissonMultiLock

**Best For**:
- Grouping locks from different Redis instances
- Coordinating across multiple independent systems
- Flexible lock composition
- When you already have RLock objects

**Example Scenarios**:
```java
// Locks from different Redis clusters
RLock lock1 = redisson1.getLock("resource1"); // Cluster 1
RLock lock2 = redisson2.getLock("resource2"); // Cluster 2
RLock lock3 = redisson3.getLock("resource3"); // Cluster 3
RedissonMultiLock multiLock = new RedissonMultiLock(lock1, lock2, lock3);

// Mix different lock types
RLock fairLock = redisson.getFairLock("fair");
RLock readLock = redisson.getReadWriteLock("rw").readLock();
RedissonMultiLock multiLock = new RedissonMultiLock(fairLock, readLock);
```

## Safety & Correctness

### redlock4j

**Safety Guarantees**:
- ✅ Deadlock-free (automatic key sorting)
- ✅ Quorum-based consistency
- ✅ All-or-nothing atomicity
- ✅ Clock drift compensation
- ✅ Validity time enforcement

**Potential Issues**:
- ⚠️ All resources must be on same Redis cluster
- ⚠️ Higher latency due to quorum requirement
- ⚠️ More network overhead (N×M operations)

### Redisson

**Safety Guarantees**:
- ✅ Flexible lock composition
- ✅ Works across different Redis instances
- ✅ Extensible failure tolerance
- ✅ Async/reactive support

**Potential Issues**:
- ⚠️ No automatic deadlock prevention
- ⚠️ Developer must ensure lock ordering
- ⚠️ Sequential acquisition (slower for many locks)
- ⚠️ No quorum mechanism by default

## Complexity Analysis

### redlock4j

**Code Complexity**: ~370 lines

**Pros**:
- ✅ Integrated Redlock implementation
- ✅ Automatic deadlock prevention
- ✅ Clear all-or-nothing semantics
- ✅ Single validity time
- ✅ Thread-local state (fast reentrancy)

**Cons**:
- ❌ Limited to single Redis cluster
- ❌ More Redis operations
- ❌ Higher network overhead
- ❌ Less flexible composition

### Redisson

**Code Complexity**: ~450 lines (with async support)

**Pros**:
- ✅ Works across multiple Redis instances
- ✅ Flexible lock composition
- ✅ Extensible (can override failedLocksLimit)
- ✅ Async/reactive support
- ✅ Can mix different lock types

**Cons**:
- ❌ No deadlock prevention
- ❌ Sequential acquisition
- ❌ More complex retry logic
- ❌ Requires careful lock ordering

## RedissonRedLock vs redlock4j MultiLock

Redisson also has `RedissonRedLock` which extends `RedissonMultiLock`:

### RedissonRedLock

```java
public class RedissonRedLock extends RedissonMultiLock {

    @Override
    protected int failedLocksLimit() {
        return locks.size() - minLocksAmount(locks);
    }

    protected int minLocksAmount(List<RLock> locks) {
        return locks.size() / 2 + 1; // Quorum
    }
}
```

**Key Difference**: Implements quorum-based failure tolerance

**Comparison with redlock4j MultiLock**:

| Feature | redlock4j MultiLock | RedissonRedLock |
|---------|---------------------|-----------------|
| **Purpose** | Multiple resources on same cluster | Multiple independent Redis instances |
| **Quorum** | Per-resource across nodes | Across different locks |
| **Deadlock Prevention** | Automatic (sorted keys) | Manual (developer responsibility) |
| **Acquisition** | Parallel per node | Sequential across locks |
| **Use Case** | Multi-resource locking | Multi-instance Redlock |

## Recommendations

### Choose redlock4j MultiLock when:

- ✅ Locking multiple resources on the same Redis cluster
- ✅ Need automatic deadlock prevention
- ✅ Require strict all-or-nothing semantics
- ✅ Want quorum-based safety per resource
- ✅ Prefer simpler, integrated solution

### Choose Redisson RedissonMultiLock when:

- ✅ Need to coordinate locks across different Redis instances
- ✅ Want to compose different lock types
- ✅ Require async/reactive support
- ✅ Can manage lock ordering manually
- ✅ Need flexible failure tolerance

### Choose Redisson RedissonRedLock when:

- ✅ Implementing Redlock across multiple Redis instances
- ✅ Each lock represents a different Redis master
- ✅ Need quorum-based distributed locking
- ✅ Can ensure proper lock ordering

## Migration Considerations

### From Redisson to redlock4j

```java
// Before (Redisson)
RLock lock1 = redisson.getLock("account:1");
RLock lock2 = redisson.getLock("account:2");
RLock lock3 = redisson.getLock("account:3");
RedissonMultiLock multiLock = new RedissonMultiLock(lock1, lock2, lock3);
multiLock.lock();
try {
    // work
} finally {
    multiLock.unlock();
}

// After (redlock4j)
MultiLock multiLock = new MultiLock(
    Arrays.asList("account:1", "account:2", "account:3"),
    redisDrivers,
    config
);
multiLock.lock();
try {
    // work
} finally {
    multiLock.unlock();
}
```

**Benefits**:
- Automatic deadlock prevention
- Quorum-based safety
- Simpler API

**Considerations**:
- All resources must be on same cluster
- Different performance characteristics

### From redlock4j to Redisson

```java
// Before (redlock4j)
MultiLock multiLock = new MultiLock(
    Arrays.asList("resource1", "resource2", "resource3"),
    redisDrivers,
    config
);

// After (Redisson) - if using multiple instances
RLock lock1 = redisson1.getLock("resource1");
RLock lock2 = redisson2.getLock("resource2");
RLock lock3 = redisson3.getLock("resource3");
RedissonRedLock multiLock = new RedissonRedLock(lock1, lock2, lock3);
```

**Benefits**:
- Can use different Redis instances
- Async/reactive support
- More flexible composition

**Considerations**:
- Must ensure consistent lock ordering
- Different acquisition semantics

## Conclusion

Both implementations serve different purposes:

**redlock4j MultiLock**:
- Designed for locking multiple resources on the same distributed Redis cluster
- Automatic deadlock prevention through key sorting
- Quorum-based safety per resource
- All-or-nothing atomic acquisition
- Simpler, more focused implementation

**Redisson RedissonMultiLock**:
- Designed for grouping independent locks (possibly from different instances)
- Flexible composition of different lock types
- Sequential acquisition with retry logic
- Requires manual deadlock prevention
- More flexible but more complex

**Redisson RedissonRedLock**:
- Implements Redlock algorithm across multiple Redis instances
- Quorum-based failure tolerance
- Similar goals to redlock4j but different scope (instances vs resources)

Choose based on your specific requirements:
- **Same cluster, multiple resources** → redlock4j MultiLock
- **Multiple instances, flexible composition** → Redisson RedissonMultiLock
- **Multiple instances, Redlock algorithm** → Redisson RedissonRedLock
    boolean acquired = successfulNodes >= config.getQuorum()
                    && validityTime > 0;

    // 4. Rollback if failed
    if (!acquired) {
        releaseAllLocks(lockValues);
    }

    return new MultiLockResult(acquired, validityTime, lockValues, ...);
}
```

**Per-Node Acquisition**:
```java
private boolean acquireAllOnNode(RedisDriver driver, Map<String, String> lockValues) {
    List<String> acquiredKeys = new ArrayList<>();

    for (String key : lockKeys) {
        if (driver.setIfNotExists(key, lockValue, timeout)) {
            acquiredKeys.add(key);
        } else {
            // Failed - rollback this node
            rollbackOnNode(driver, lockValues, acquiredKeys);
            return false;
        }
    }
    return true;
}
```

**Flow**:
1. Generate unique lock values for all keys
2. For each Redis node:
   - Try to acquire ALL locks
   - If any fails, rollback that node
3. Check if quorum achieved
4. If not, release all acquired locks


