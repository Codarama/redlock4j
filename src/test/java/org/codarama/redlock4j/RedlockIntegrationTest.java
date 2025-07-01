package org.codarama.redlock4j;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * Integration tests for Redlock functionality.
 * These tests require running Redis instances and are disabled by default.
 * 
 * To run these tests:
 * 1. Start 3 Redis instances on ports 6379, 6380, 6381
 * 2. Remove the @Disabled annotation
 * 3. Run the tests
 */
@Disabled("Requires running Redis instances")
public class RedlockIntegrationTest {
    
    private RedlockConfiguration createTestConfiguration() {
        return RedlockConfiguration.builder()
            .addRedisNode("localhost", 6379)
            .addRedisNode("localhost", 6380)
            .addRedisNode("localhost", 6381)
            .defaultLockTimeout(10, TimeUnit.SECONDS)
            .retryDelay(100, TimeUnit.MILLISECONDS)
            .maxRetryAttempts(3)
            .lockAcquisitionTimeout(5, TimeUnit.SECONDS)
            .build();
    }
    
    @Test
    public void testJedisBasicLockOperations() {
        RedlockConfiguration config = createTestConfiguration();
        
        try (RedlockManager manager = RedlockManager.withJedis(config)) {
            assertTrue(manager.isHealthy(), "Manager should be healthy with connected Redis nodes");
            assertEquals(3, manager.getConnectedNodeCount(), "Should have 3 connected nodes");
            assertEquals(2, manager.getQuorum(), "Quorum should be 2 for 3 nodes");
            
            Lock lock = manager.createLock("test-lock-jedis");
            
            // Test basic lock/unlock
            assertTrue(lock.tryLock(), "Should be able to acquire lock");
            
            if (lock instanceof RedlockLock) {
                RedlockLock redlockLock = (RedlockLock) lock;
                assertTrue(redlockLock.isHeldByCurrentThread(), "Lock should be held by current thread");
                assertTrue(redlockLock.getRemainingValidityTime() > 0, "Lock should have remaining validity time");
            }
            
            lock.unlock();
            
            if (lock instanceof RedlockLock) {
                RedlockLock redlockLock = (RedlockLock) lock;
                assertFalse(redlockLock.isHeldByCurrentThread(), "Lock should not be held after unlock");
            }
        }
    }
    
    @Test
    public void testLettuceBasicLockOperations() {
        RedlockConfiguration config = createTestConfiguration();
        
        try (RedlockManager manager = RedlockManager.withLettuce(config)) {
            assertTrue(manager.isHealthy(), "Manager should be healthy with connected Redis nodes");
            assertEquals(3, manager.getConnectedNodeCount(), "Should have 3 connected nodes");
            
            Lock lock = manager.createLock("test-lock-lettuce");
            
            // Test basic lock/unlock
            assertTrue(lock.tryLock(), "Should be able to acquire lock");
            lock.unlock();
        }
    }
    
    @Test
    public void testLockTimeout() throws InterruptedException {
        RedlockConfiguration config = createTestConfiguration();
        
        try (RedlockManager manager = RedlockManager.withJedis(config)) {
            Lock lock = manager.createLock("test-timeout-lock");
            
            // Test tryLock with timeout
            assertTrue(lock.tryLock(1, TimeUnit.SECONDS), "Should acquire lock within timeout");
            lock.unlock();
            
            // Test immediate tryLock
            assertTrue(lock.tryLock(), "Should acquire lock immediately");
            lock.unlock();
        }
    }
    
    @Test
    public void testConcurrentLockAccess() throws InterruptedException {
        RedlockConfiguration config = createTestConfiguration();
        
        try (RedlockManager manager = RedlockManager.withJedis(config)) {
            Lock lock1 = manager.createLock("concurrent-test-lock");
            Lock lock2 = manager.createLock("concurrent-test-lock"); // Same key
            
            // First lock should succeed
            assertTrue(lock1.tryLock(), "First lock should succeed");
            
            // Second lock should fail (same resource)
            assertFalse(lock2.tryLock(), "Second lock should fail for same resource");
            
            // Release first lock
            lock1.unlock();
            
            // Now second lock should succeed
            assertTrue(lock2.tryLock(), "Second lock should succeed after first is released");
            lock2.unlock();
        }
    }
    
    @Test
    public void testLockWithCustomConfiguration() {
        RedlockConfiguration config = RedlockConfiguration.builder()
            .addRedisNode(RedisNodeConfiguration.builder()
                .host("localhost")
                .port(6379)
                .connectionTimeoutMs(1000)
                .socketTimeoutMs(1000)
                .build())
            .addRedisNode(RedisNodeConfiguration.builder()
                .host("localhost")
                .port(6380)
                .connectionTimeoutMs(1000)
                .socketTimeoutMs(1000)
                .build())
            .addRedisNode(RedisNodeConfiguration.builder()
                .host("localhost")
                .port(6381)
                .connectionTimeoutMs(1000)
                .socketTimeoutMs(1000)
                .build())
            .defaultLockTimeout(5, TimeUnit.SECONDS)
            .retryDelay(50, TimeUnit.MILLISECONDS)
            .maxRetryAttempts(2)
            .clockDriftFactor(0.02)
            .build();
        
        try (RedlockManager manager = RedlockManager.withJedis(config)) {
            Lock lock = manager.createLock("custom-config-lock");
            
            assertTrue(lock.tryLock(), "Should acquire lock with custom configuration");
            lock.unlock();
        }
    }
    
    @Test
    public void testManagerLifecycle() {
        RedlockConfiguration config = createTestConfiguration();
        
        RedlockManager manager = RedlockManager.withJedis(config);
        assertTrue(manager.isHealthy(), "Manager should be healthy when created");
        
        Lock lock = manager.createLock("lifecycle-test-lock");
        assertTrue(lock.tryLock(), "Should be able to create and use locks");
        lock.unlock();
        
        manager.close();
        assertEquals(0, manager.getConnectedNodeCount(), "Should have no connected nodes after close");
        assertFalse(manager.isHealthy(), "Manager should not be healthy after close");
        
        // Should throw exception when trying to create locks after close
        assertThrows(RedlockException.class, () -> {
            manager.createLock("should-fail");
        });
    }
    
    @Test
    public void testInvalidLockKey() {
        RedlockConfiguration config = createTestConfiguration();
        
        try (RedlockManager manager = RedlockManager.withJedis(config)) {
            // Test null key
            assertThrows(IllegalArgumentException.class, () -> {
                manager.createLock(null);
            });
            
            // Test empty key
            assertThrows(IllegalArgumentException.class, () -> {
                manager.createLock("");
            });
            
            // Test whitespace-only key
            assertThrows(IllegalArgumentException.class, () -> {
                manager.createLock("   ");
            });
        }
    }
}
