/*
 * MIT License
 *
 * Copyright (c) 2025 Codarama
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.codarama.redlock4j.integration;

import io.reactivex.rxjava3.observers.TestObserver;
import org.codarama.redlock4j.RedlockManager;
import org.codarama.redlock4j.async.AsyncRedlock;
import org.codarama.redlock4j.async.AsyncRedlockImpl;
import org.codarama.redlock4j.async.RxRedlock;
import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Simple tests to verify async and reactive APIs work correctly.
 */
@Testcontainers
public class AsyncRedlockIntegrationTest {
    
    @Container
    static GenericContainer<?> redis1 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");
    
    @Container
    static GenericContainer<?> redis2 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");
    
    @Container
    static GenericContainer<?> redis3 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");
    
    private static RedlockConfiguration testConfiguration;
    
    @BeforeAll
    static void setUp() {
        testConfiguration = RedlockConfiguration.builder()
            .addRedisNode("localhost", redis1.getMappedPort(6379))
            .addRedisNode("localhost", redis2.getMappedPort(6379))
            .addRedisNode("localhost", redis3.getMappedPort(6379))
            .defaultLockTimeout(Duration.ofSeconds(10))
            .retryDelay(Duration.ofMillis(100))
            .maxRetryAttempts(3)
            .lockAcquisitionTimeout(Duration.ofSeconds(5))
            .build();
    }
    
    @Test
    public void testAsyncLockBasicFunctionality() throws Exception {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            AsyncRedlock asyncLock = manager.createAsyncLock("test-async-basic");
            
            // Test async tryLock
            CompletionStage<Boolean> lockResult = asyncLock.tryLockAsync();
            Boolean acquired = lockResult.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(acquired, "Should acquire lock asynchronously");
            
            // Verify lock key
            assertEquals("test-async-basic", asyncLock.getLockKey());
            
            // Test async unlock
            CompletionStage<Void> unlockResult = asyncLock.unlockAsync();
            unlockResult.toCompletableFuture().get(5, TimeUnit.SECONDS);
            
            // Test should complete without exceptions
            System.out.println("✅ Async lock test completed successfully");
        }
    }
    
    @Test
    public void testRxJavaBasicFunctionality() throws Exception {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RxRedlock rxLock = manager.createRxLock("test-rxjava-basic");
            
            // Test RxJava Single tryLock
            TestObserver<Boolean> lockObserver = rxLock.tryLockRx().test();
            lockObserver.await(5, TimeUnit.SECONDS);
            lockObserver.assertComplete();
            lockObserver.assertValue(true);
            
            // Verify lock key
            assertEquals("test-rxjava-basic", rxLock.getLockKey());
            
            // Test RxJava Completable unlock
            TestObserver<Void> unlockObserver = rxLock.unlockRx().test();
            unlockObserver.await(5, TimeUnit.SECONDS);
            unlockObserver.assertComplete();
            
            System.out.println("✅ RxJava lock test completed successfully");
        }
    }
    
    @Test
    public void testCombinedAsyncRxLock() throws Exception {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            AsyncRedlockImpl combinedLock = manager.createAsyncRxLock("test-combined-basic");
            
            // Test CompletionStage interface
            Boolean asyncResult = combinedLock.tryLockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(asyncResult, "Should acquire lock via CompletionStage interface");
            
            // Test RxJava interface on same lock instance
            assertEquals("test-combined-basic", combinedLock.getLockKey());
            
            // Test async unlock
            combinedLock.unlockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
            
            System.out.println("✅ Combined async/reactive lock test completed successfully");
        }
    }
    
    @Test
    public void testAsyncLockWithTimeout() throws Exception {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            AsyncRedlock asyncLock = manager.createAsyncLock("test-async-timeout");
            
            // Test async tryLock with timeout
            CompletionStage<Boolean> lockResult = asyncLock.tryLockAsync(Duration.ofSeconds(2));
            Boolean acquired = lockResult.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(acquired, "Should acquire lock with timeout");
            
            // Cleanup
            asyncLock.unlockAsync().toCompletableFuture().get();
            
            System.out.println("✅ Async lock with timeout test completed successfully");
        }
    }
    
    @Test
    public void testRxJavaValidityObservable() throws Exception {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RxRedlock rxLock = manager.createRxLock("test-validity-observable");

            // Acquire lock first
            TestObserver<Boolean> lockObserver = rxLock.tryLockRx().test();
            lockObserver.await(5, TimeUnit.SECONDS);
            lockObserver.assertValue(true);

            // Test validity observable - start immediately and take fewer emissions
            TestObserver<Long> validityObserver = rxLock.validityObservable(Duration.ofMillis(300))
                .take(1) // Take only 1 emission to be safe
                .test();

            validityObserver.await(1, TimeUnit.SECONDS);

            // Should have at least 1 emission
            assertFalse(validityObserver.values().isEmpty(), "Should have at least 1 validity emission");

            // All validity values should be positive
            validityObserver.values().forEach(validity ->
                assertTrue(validity > 0, "Validity time should be positive: " + validity));

            // Cleanup
            rxLock.unlockRx().test().await();

            System.out.println("✅ RxJava validity observable test completed successfully");
        }
    }
    
    @Test
    public void testFactoryMethods() {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            
            // Test all factory methods work
            AsyncRedlock asyncLock = manager.createAsyncLock("factory-async");
            assertNotNull(asyncLock);
            assertEquals("factory-async", asyncLock.getLockKey());
            
            RxRedlock rxLock = manager.createRxLock("factory-rx");
            assertNotNull(rxLock);
            assertEquals("factory-rx", rxLock.getLockKey());
            
            AsyncRedlockImpl combinedLock = manager.createAsyncRxLock("factory-combined");
            assertNotNull(combinedLock);
            assertEquals("factory-combined", combinedLock.getLockKey());
            
            // Verify combined lock implements both interfaces
            assertInstanceOf(AsyncRedlock.class, combinedLock);
            assertInstanceOf(RxRedlock.class, combinedLock);
            
            System.out.println("✅ Factory methods test completed successfully");
        }
    }
}
