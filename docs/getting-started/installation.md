# Installation

## Maven

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>org.codarama</groupId>
    <artifactId>redlock4j</artifactId>
    <version>1.1.0</version>
</dependency>
```

You'll also need to add a Redis client dependency. Redlock4j supports both Jedis and Lettuce:

### Using Jedis

```xml
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>7.1.0</version>
</dependency>
```

### Using Lettuce

```xml
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
    <version>7.1.0.RELEASE</version>
</dependency>
```

## Gradle

Add the following to your `build.gradle`:

```gradle
dependencies {
    implementation 'org.codarama:redlock4j:1.1.0'
    
    // Choose one Redis client:
    // For Jedis:
    implementation 'redis.clients:jedis:7.1.0'
    
    // Or for Lettuce:
    implementation 'io.lettuce:lettuce-core:7.1.0.RELEASE'
}
```

## Requirements

- **Java 8** or higher
- **Redis 2.6.12** or higher (for Lua script support)
- One of the supported Redis clients:
    - Jedis 3.0+ or 4.0+
    - Lettuce 5.0+ or 6.0+

## Verifying Installation

After adding the dependencies, verify the installation by creating a simple test:

```java
import org.codarama.redlock4j.Redlock;
import redis.clients.jedis.JedisPool;

public class RedlockTest {
    public static void main(String[] args) {
        JedisPool pool = new JedisPool("localhost", 6379);
        Redlock redlock = new Redlock(pool);
        System.out.println("Redlock4j is ready!");
        pool.close();
    }
}
```

## Next Steps

- [Quick Start Guide](quick-start.md) - Learn the basics
