# Advanced Locking

Redlock4j provides advanced distributed synchronization primitives beyond basic locks.

## Fair Lock

Fair locks ensure FIFO ordering for lock acquisition, preventing thread starvation.

### Usage

```java
Lock fairLock = redlockManager.createFairLock("fair-resource");

fairLock.lock();
try {
    // Critical section - guaranteed FIFO ordering
    performWork();
} finally {
    fairLock.unlock();
}
```

### Use Cases

- High-contention scenarios requiring fairness
- Preventing thread starvation
- Maintaining request ordering in distributed systems

## Multi-Lock

Atomic acquisition of multiple resources simultaneously, preventing deadlocks.

### Usage

```java
MultiLock multiLock = redlockManager.createMultiLock("resource-1", "resource-2", "resource-3");

multiLock.lock();
try {
    // All resources locked atomically
    processMultipleResources();
} finally {
    multiLock.unlock();
}
```

### Use Cases

- Database transactions across multiple tables
- Coordinating access to multiple shared resources
- Preventing circular wait deadlocks

## Read-Write Lock

Separate read and write locks for improved concurrency.

### Usage

```java
ReadWriteLock rwLock = redlockManager.createReadWriteLock("shared-data");

// Multiple readers can acquire simultaneously
rwLock.readLock().lock();
try {
    // Read operation
    data = readData();
} finally {
    rwLock.readLock().unlock();
}

// Writers get exclusive access
rwLock.writeLock().lock();
try {
    // Write operation
    writeData(newData);
} finally {
    rwLock.writeLock().unlock();
}
```

### Use Cases

- Read-heavy workloads
- Caching scenarios
- Shared configuration data

## Distributed Semaphore

Control access to a limited number of resources.

### Usage

```java
Semaphore semaphore = redlockManager.createSemaphore("resource-pool", 5); // 5 permits

semaphore.acquire();
try {
    // Use limited resource
    useResource();
} finally {
    semaphore.release();
}
```

### Use Cases

- Connection pool management
- Rate limiting
- Resource quota enforcement

## CountDownLatch

Coordinate multiple distributed processes.

### Usage

```java
CountDownLatch latch = redlockManager.createCountDownLatch("startup-latch", 3);

// Worker threads
latch.countDown();

// Coordinator thread
latch.await(); // Waits until count reaches 0
```

### Use Cases

- Distributed initialization
- Barrier synchronization
- Coordinating parallel tasks

## Best Practices

### Choose the Right Primitive

- **Basic Lock**: Simple mutual exclusion
- **Fair Lock**: When ordering matters
- **Multi-Lock**: Multiple resources needed atomically
- **Read-Write Lock**: Read-heavy workloads
- **Semaphore**: Limited resource pool
- **CountDownLatch**: Coordination between processes

### Timeout Configuration

Always use timeouts to prevent indefinite blocking:

```java
boolean acquired = lock.tryLock(5, TimeUnit.SECONDS);
if (acquired) {
    try {
        // Critical section
    } finally {
        lock.unlock();
    }
} else {
    // Handle timeout
}
```

### Error Handling

Properly handle failures:

```java
Lock lock = null;
try {
    lock = redlockManager.createLock("resource");
    lock.lock();
    // Critical section
} catch (Exception e) {
    logger.error("Error in critical section", e);
} finally {
    if (lock != null) {
        try {
            lock.unlock();
        } catch (Exception e) {
            logger.error("Error releasing lock", e);
        }
    }
}
```

## Performance Considerations

- **Fair locks** have higher overhead than regular locks
- **Multi-locks** require more Redis operations
- **Read-write locks** optimize for read-heavy scenarios
- **Semaphores** scale with permit count

## Next Steps

- [Best Practices](best-practices.md) - Follow recommended practices
- [API Reference](../api/core.md) - Detailed API documentation

For complete details, see [ADVANCED_LOCKING.md](https://github.com/tishun/redlock4j/blob/main/ADVANCED_LOCKING.md) in the repository.

