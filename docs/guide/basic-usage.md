# Basic Usage

This guide covers common usage patterns and scenarios for Redlock4j.

## Simple Lock Pattern

The most basic usage pattern:

```java
Lock lock = redlock.lock("resource-id", 10000);
if (lock != null) {
    try {
        // Critical section
    } finally {
        redlock.unlock(lock);
    }
}
```

## Try-Lock Pattern

Attempt to acquire a lock without retries:

```java
Lock lock = redlock.tryLock("resource-id", 10000);
if (lock != null) {
    try {
        // Got the lock immediately
    } finally {
        redlock.unlock(lock);
    }
} else {
    // Lock not available, handle accordingly
}
```

## Lock with Timeout

Wait for a lock with a timeout:

```java
Lock lock = redlock.lock("resource-id", 10000, 5000); // 5 second timeout
if (lock != null) {
    try {
        // Acquired lock within timeout
    } finally {
        redlock.unlock(lock);
    }
} else {
    // Timeout expired
}
```

## Extending Lock Duration

If your operation takes longer than expected, you can extend the lock:

```java
Lock lock = redlock.lock("resource-id", 10000);
if (lock != null) {
    try {
        // Do some work
        performPartialWork();
        
        // Need more time, extend the lock
        boolean extended = redlock.extend(lock, 10000);
        if (extended) {
            // Continue working
            performMoreWork();
        }
    } finally {
        redlock.unlock(lock);
    }
}
```

## Checking Lock Validity

Check if a lock is still valid:

```java
Lock lock = redlock.lock("resource-id", 10000);
if (lock != null) {
    try {
        performWork();
        
        if (redlock.isValid(lock)) {
            // Lock is still valid
            performMoreWork();
        } else {
            // Lock expired or was released
            handleExpiredLock();
        }
    } finally {
        redlock.unlock(lock);
    }
}
```

## Multiple Resources

Lock multiple resources atomically:

```java
String[] resources = {"resource-1", "resource-2", "resource-3"};
Lock lock = redlock.lock(resources, 10000);

if (lock != null) {
    try {
        // All resources are locked
        processMultipleResources();
    } finally {
        redlock.unlock(lock);
    }
}
```

## Reentrant Locks

Redlock4j supports reentrant locks (same thread can acquire the same lock multiple times):

```java
Lock lock1 = redlock.lock("resource-id", 10000);
if (lock1 != null) {
    try {
        // First acquisition
        
        Lock lock2 = redlock.lock("resource-id", 10000);
        if (lock2 != null) {
            try {
                // Second acquisition by same thread
            } finally {
                redlock.unlock(lock2);
            }
        }
    } finally {
        redlock.unlock(lock1);
    }
}
```

## Error Handling

Proper error handling is crucial:

```java
Lock lock = null;
try {
    lock = redlock.lock("resource-id", 10000);
    if (lock != null) {
        // Critical section
        performCriticalOperation();
    } else {
        // Failed to acquire lock
        logger.warn("Could not acquire lock for resource-id");
        handleLockFailure();
    }
} catch (Exception e) {
    logger.error("Error during critical section", e);
    handleError(e);
} finally {
    if (lock != null) {
        try {
            redlock.unlock(lock);
        } catch (Exception e) {
            logger.error("Error releasing lock", e);
        }
    }
}
```

## Using with Try-With-Resources

If your Lock implementation supports AutoCloseable:

```java
try (Lock lock = redlock.lock("resource-id", 10000)) {
    if (lock != null) {
        // Critical section
        performCriticalOperation();
    }
} // Lock automatically released
```

## Common Patterns

### Singleton Task Execution

Ensure only one instance executes a task:

```java
public void executeScheduledTask() {
    Lock lock = redlock.lock("scheduled-task-id", 60000);
    if (lock != null) {
        try {
            // Only one instance will execute this
            performScheduledTask();
        } finally {
            redlock.unlock(lock);
        }
    } else {
        // Another instance is already executing
        logger.info("Task already running on another instance");
    }
}
```

### Resource Pool Management

Manage access to a limited resource pool:

```java
public void processWithResource(String resourceId) {
    Lock lock = redlock.lock("resource-pool:" + resourceId, 30000);
    if (lock != null) {
        try {
            Resource resource = acquireResource(resourceId);
            processResource(resource);
        } finally {
            redlock.unlock(lock);
        }
    }
}
```

## Next Steps

- [Advanced Locking](advanced-locking.md) - Learn about advanced features
- [Best Practices](best-practices.md) - Follow recommended practices

