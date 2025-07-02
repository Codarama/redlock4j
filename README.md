# Redlock4j

[![CI](https://github.com/Codarama/redlock4j/actions/workflows/ci.yml/badge.svg)](https://github.com/Codarama/redlock4j/actions/workflows/ci.yml)
[![Nightly Tests](https://github.com/Codarama/redlock4j/actions/workflows/nightly.yml/badge.svg)](https://github.com/Codarama/redlock4j/actions/workflows/nightly.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-8%2B-blue.svg)](https://openjdk.java.net/)

A Java implementation of the [Redlock distributed locking algorithm](https://redis.io/docs/latest/develop/use/patterns/distributed-locks/) that implements the standard Java `java.util.concurrent.locks.Lock` interface.

## Features

- **Standard Java Lock Interface**: Implements `java.util.concurrent.locks.Lock` for seamless integration
- **Multiple Redis Drivers**: Supports both Jedis and Lettuce Redis clients
- **Builder Pattern Configuration**: Easy-to-use configuration with sensible defaults
- **Thread-Safe**: Proper thread-local lock state management
- **Fault Tolerant**: Works with Redis node failures as long as quorum is maintained
- **Configurable**: Customizable timeouts, retry logic, and clock drift compensation

## Requirements

- Java 8 or higher
- At least 3 Redis instances for proper Redlock operation
- Either Jedis or Lettuce Redis client library

## Dependencies

Add the following dependencies to your `pom.xml`:

```xml
<!-- For Jedis support -->
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>5.1.0</version>
</dependency>

<!-- OR for Lettuce support -->
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
    <version>6.3.0.RELEASE</version>
</dependency>

<!-- Logging -->
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>2.0.9</version>
</dependency>
```

## Quick Start

### 1. Add Dependencies

Add this library and your preferred Redis client to your `pom.xml`:

```xml
<!-- Redlock4j from Maven Central -->
<dependency>
    <groupId>org.codarama</groupId>
    <artifactId>redlock4j</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Choose either Jedis OR Lettuce -->
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>5.1.0</version>
</dependency>
<!-- OR -->
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
    <version>6.3.0.RELEASE</version>
</dependency>
```

### 2. Configure Redis Nodes

```java
RedlockConfiguration config = RedlockConfiguration.builder()
    .addRedisNode("redis1.example.com", 6379)
    .addRedisNode("redis2.example.com", 6379)
    .addRedisNode("redis3.example.com", 6379)
    .defaultLockTimeout(30, TimeUnit.SECONDS)
    .retryDelay(200, TimeUnit.MILLISECONDS)
    .maxRetryAttempts(3)
    .build();
```

### 3. Create RedlockManager

```java
// Using Jedis
try (RedlockManager redlockManager = RedlockManager.withJedis(config)) {
    // Use the manager...
}

// OR using Lettuce
try (RedlockManager redlockManager = RedlockManager.withLettuce(config)) {
    // Use the manager...
}
```

### 4. Use Distributed Locks

```java
Lock lock = redlockManager.createLock("my-resource-key");

// Standard Lock interface usage
lock.lock();
try {
    // Critical section
    performCriticalWork();
} finally {
    lock.unlock();
}

// Try lock with timeout
if (lock.tryLock(5, TimeUnit.SECONDS)) {
    try {
        // Critical section
        performCriticalWork();
    } finally {
        lock.unlock();
    }
} else {
    // Failed to acquire lock
    handleLockFailure();
}
```

## Testing with Testcontainers

The project includes comprehensive integration tests that use [Testcontainers](https://www.testcontainers.org/) to automatically spin up Redis containers. This means you can run the full test suite without manually setting up Redis instances.

### Running Integration Tests

```bash
# Run all tests (including integration tests with Testcontainers)
mvn test

# Run only integration tests
mvn test -Dtest=RedlockIntegrationTest
```

### Adding Testcontainers to Your Project

If you want to use Testcontainers for testing your own Redlock-based applications:

```xml
<!-- Add to your test dependencies -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>1.19.3</version>
    <scope>test</scope>
</dependency>

<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <version>1.19.3</version>
    <scope>test</scope>
</dependency>
```

### Example Test with Testcontainers

```java
@Testcontainers
public class MyRedlockTest {

    @Container
    static GenericContainer<?> redis1 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static GenericContainer<?> redis2 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static GenericContainer<?> redis3 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Test
    public void testDistributedLocking() {
        RedlockConfiguration config = RedlockConfiguration.builder()
            .addRedisNode("localhost", redis1.getMappedPort(6379))
            .addRedisNode("localhost", redis2.getMappedPort(6379))
            .addRedisNode("localhost", redis3.getMappedPort(6379))
            .build();

        try (RedlockManager manager = RedlockManager.withJedis(config)) {
            Lock lock = manager.createLock("test-resource");
            assertTrue(lock.tryLock());
            // Your test logic here
            lock.unlock();
        }
    }
}
```

## Configuration Options

### RedlockConfiguration

| Option | Default | Description |
|--------|---------|-------------|
| `defaultLockTimeout` | 30 seconds | How long locks are held before auto-expiring |
| `retryDelay` | 200ms | Base delay between lock acquisition attempts |
| `maxRetryAttempts` | 3 | Maximum number of retry attempts |
| `clockDriftFactor` | 0.01 | Factor to account for clock drift between nodes |
| `lockAcquisitionTimeout` | 10 seconds | Maximum time to wait when calling `lock()` |

### RedisNodeConfiguration

| Option | Default | Description |
|--------|---------|-------------|
| `host` | localhost | Redis server hostname |
| `port` | 6379 | Redis server port |
| `password` | null | Redis password (if required) |
| `database` | 0 | Redis database number |
| `connectionTimeoutMs` | 2000 | Connection timeout in milliseconds |
| `socketTimeoutMs` | 2000 | Socket timeout in milliseconds |

## Advanced Usage

### Custom Node Configuration

```java
RedisNodeConfiguration node1 = RedisNodeConfiguration.builder()
    .host("redis1.example.com")
    .port(6379)
    .password("secret")
    .database(1)
    .connectionTimeoutMs(3000)
    .socketTimeoutMs(3000)
    .build();

RedlockConfiguration config = RedlockConfiguration.builder()
    .addRedisNode(node1)
    .addRedisNode("redis2.example.com", 6379, "secret")
    .addRedisNode("redis3.example.com", 6379, "secret")
    .build();
```

### Checking Lock State

```java
RedlockLock redlockLock = (RedlockLock) lock;

if (redlockLock.isHeldByCurrentThread()) {
    long remainingTime = redlockLock.getRemainingValidityTime();
    System.out.println("Lock valid for " + remainingTime + "ms more");
}
```

### Health Monitoring

```java
if (redlockManager.isHealthy()) {
    System.out.println("Manager has " + redlockManager.getConnectedNodeCount() + 
                      " connected nodes (quorum: " + redlockManager.getQuorum() + ")");
} else {
    System.err.println("Not enough Redis nodes connected for reliable operation");
}
```

## How It Works

This implementation follows the Redlock algorithm as specified by Redis:

1. **Lock Acquisition**: Attempts to acquire the lock on all Redis nodes sequentially
2. **Quorum Check**: Requires majority of nodes (N/2+1) to successfully acquire the lock
3. **Validity Calculation**: Accounts for time elapsed and clock drift when determining lock validity
4. **Automatic Cleanup**: Releases partial locks if quorum is not achieved
5. **Safe Release**: Uses Lua script to ensure only the lock holder can release the lock

## Thread Safety

- Each thread maintains its own lock state using `ThreadLocal`
- Multiple threads can safely use the same `RedlockLock` instance
- Lock state is automatically cleaned up when locks are released

## Error Handling

- `RedlockException`: Thrown for lock-related errors
- `RedisDriverException`: Thrown for Redis communication errors
- Automatic retry with exponential backoff and jitter
- Graceful degradation when Redis nodes are unavailable

## Best Practices

1. **Use at least 3 Redis nodes** for proper fault tolerance
2. **Set appropriate timeouts** based on your use case
3. **Always use try-finally blocks** to ensure locks are released
4. **Monitor Redis node health** and connection status
5. **Consider lock validity time** for long-running operations
6. **Use unique lock keys** to avoid conflicts between different resources

## CI/CD and Testing

This project uses GitHub Actions for continuous integration and comprehensive testing:

### Automated Testing
- **Pull Request Validation**: Every PR is automatically tested with compilation, unit tests, and integration tests
- **Nightly Comprehensive Tests**: Full test suite including performance tests runs every night
- **Multi-Platform Testing**: Tests run on Ubuntu, Windows, and macOS
- **Multi-Java Version**: Tested against Java 8, 11, 17, and 21
- **Multi-Redis Version**: Tested against Redis 6, 7, and latest versions

### Security and Quality
- **Dependency Security Scanning**: OWASP dependency check for known vulnerabilities
- **Code Coverage**: JaCoCo integration for test coverage reporting
- **License Compliance**: Automated verification of license headers
- **Code Style**: Basic formatting and style checks

### Workflows
- **CI Workflow** (`.github/workflows/ci.yml`): Runs on every push and PR
- **Nightly Workflow** (`.github/workflows/nightly.yml`): Comprehensive testing every night
- **PR Validation** (`.github/workflows/pr-validation.yml`): Detailed PR validation with comments
- **Release Workflow** (`.github/workflows/release.yml`): Automated Maven Central publishing

### Running Tests Locally
```bash
# Run all tests
mvn test

# Run only unit tests
mvn test -Dtest=RedlockConfigurationTest

# Run only integration tests
mvn test -Dtest=RedlockIntegrationTest

# Run with coverage
mvn test jacoco:report

# Security scan
mvn org.owasp:dependency-check-maven:check
```

## Releases and Maven Central

Redlock4j is automatically published to Maven Central when new GitHub releases are created.

### Latest Release

The latest stable version is available on Maven Central:

**Maven:**
```xml
<dependency>
    <groupId>org.codarama</groupId>
    <artifactId>redlock4j</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'org.codarama:redlock4j:1.0.0'
```

### Release Process

1. **Automated Publishing**: New GitHub releases automatically trigger Maven Central publication
2. **Quality Gates**: All tests must pass before publishing
3. **Artifact Signing**: All artifacts are GPG signed for security
4. **Documentation**: Javadoc and sources are included with each release

For detailed release information, see [RELEASE.md](RELEASE.md).

### Version History

- **1.0.0** - Initial release with full Redlock implementation
- **Future releases** - Check [GitHub Releases](https://github.com/Codarama/redlock4j/releases) for the latest

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

Copyright (c) 2025 Codarama
