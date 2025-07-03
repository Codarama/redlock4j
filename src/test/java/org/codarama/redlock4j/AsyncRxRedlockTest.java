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
package org.codarama.redlock4j;

import io.reactivex.rxjava3.observers.TestObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Tests for asynchronous CompletionStage and RxJava reactive APIs.
 */
@Testcontainers
public class AsyncRxRedlockTest {
    
    // Create 3 Redis containers for testing
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
        // Create configuration with dynamic ports from containers
        testConfiguration = RedlockConfiguration.builder()
            .addRedisNode("localhost", redis1.getMappedPort(6379))
            .addRedisNode("localhost", redis2.getMappedPort(6379))
            .addRedisNode("localhost", redis3.getMappedPort(6379))
            .defaultLockTimeout(10, TimeUnit.SECONDS)
            .retryDelay(100, TimeUnit.MILLISECONDS)
            .maxRetryAttempts(3)
            .lockAcquisitionTimeout(5, TimeUnit.SECONDS)
            .build();
    }
    
    @Test
    public void testCompletionStageAsyncLock() throws Exception {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            AsyncRedlock asyncLock = manager.createAsyncLock("test-completion-stage-lock");
            
            // Test async tryLock
            CompletionStage<Boolean> lockResult = asyncLock.tryLockAsync();
            Boolean acquired = lockResult.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(acquired, "Should acquire lock asynchronously");
            
            // Verify lock state
            assertTrue(asyncLock.isHeldByCurrentThread(), "Lock should be held by current thread");
            assertTrue(asyncLock.getRemainingValidityTime() > 0, "Lock should have remaining validity time");
            assertEquals("test-completion-stage-lock", asyncLock.getLockKey());
            
            // Test async unlock
            CompletionStage<Void> unlockResult = asyncLock.unlockAsync();
            unlockResult.toCompletableFuture().get(5, TimeUnit.SECONDS);
            
            assertFalse(asyncLock.isHeldByCurrentThread(), "Lock should not be held after unlock");
        }
    }
    
    @Test
    public void testAsyncLockWithTimeout() throws Exception {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            AsyncRedlock asyncLock = manager.createAsyncLock("test-async-timeout");
            
            // Test async tryLock with timeout
            CompletionStage<Boolean> lockResult = asyncLock.tryLockAsync(2, TimeUnit.SECONDS);
            Boolean acquired = lockResult.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(acquired, "Should acquire lock with timeout");
            
            // Test blocking async lock
            AsyncRedlock asyncLock2 = manager.createAsyncLock("test-async-blocking");
            CompletionStage<Void> blockingResult = asyncLock2.lockAsync();
            blockingResult.toCompletableFuture().get(10, TimeUnit.SECONDS);
            
            assertTrue(asyncLock2.isHeldByCurrentThread(), "Blocking lock should be acquired");
            
            // Cleanup
            asyncLock.unlockAsync().toCompletableFuture().get();
            asyncLock2.unlockAsync().toCompletableFuture().get();
        }
    }
    
    @Test
    public void testRxJavaReactiveLock() throws Exception {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RxRedlock rxLock = manager.createRxLock("test-rxjava-lock");
            
            // Test RxJava Single tryLock
            TestObserver<Boolean> lockObserver = rxLock.tryLockRx().test();
            lockObserver.await(5, TimeUnit.SECONDS);
            lockObserver.assertComplete();
            lockObserver.assertValue(true);
            
            // Verify lock state
            assertTrue(rxLock.isHeldByCurrentThread(), "Lock should be held by current thread");
            assertEquals("test-rxjava-lock", rxLock.getLockKey());
            
            // Test RxJava Completable unlock
            TestObserver<Void> unlockObserver = rxLock.unlockRx().test();
            unlockObserver.await(5, TimeUnit.SECONDS);
            unlockObserver.assertComplete();
            
            assertFalse(rxLock.isHeldByCurrentThread(), "Lock should not be held after unlock");
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

            // Test validity observable - start it immediately after lock acquisition
            TestObserver<Long> validityObserver = rxLock.validityObservable(200, TimeUnit.MILLISECONDS)
                .take(2) // Take only 2 emissions to be safe
                .test();

            // Wait a bit for emissions
            validityObserver.await(1, TimeUnit.SECONDS);

            // Should have at least 1 emission, might have 2
            assertFalse(validityObserver.values().isEmpty(), "Should have at least 1 validity emission");

            // All validity values should be positive
            validityObserver.values().forEach(validity ->
                assertTrue(validity > 0, "Validity time should be positive: " + validity));

            // Cleanup
            rxLock.unlockRx().test().await();
        }
    }
    
    @Test
    public void testRxJavaLockStateObservable() throws Exception {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RxRedlock rxLock = manager.createRxLock("test-lock-state-observable");

            // Subscribe to lock state changes
            TestObserver<RxRedlock.LockState> stateObserver = rxLock.lockStateObservable()
                .take(3) // RELEASED -> ACQUIRING -> ACQUIRED
                .test();

            // Small delay to ensure subscription is active
            Thread.sleep(100);

            // Acquire lock (should trigger state changes)
            rxLock.tryLockRx().test().await();

            // Wait for state changes
            stateObserver.await(3, TimeUnit.SECONDS);

            // Should have at least 2 states (initial RELEASED and final state)
            assertTrue(stateObserver.values().size() >= 2, "Should have at least 2 state changes");

            // First state should be RELEASED (initial state)
            assertEquals(RxRedlock.LockState.RELEASED, stateObserver.values().get(0));

            // Last state should be ACQUIRED (if lock was successful)
            RxRedlock.LockState lastState = stateObserver.values().get(stateObserver.values().size() - 1);
            assertTrue(lastState == RxRedlock.LockState.ACQUIRED || lastState == RxRedlock.LockState.ACQUIRING,
                "Last state should be ACQUIRED or ACQUIRING, but was: " + lastState);

            // Cleanup
            rxLock.unlockRx().test().await();
        }
    }
    
    @Test
    public void testRxJavaRetryLogic() throws Exception {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            RxRedlock rxLock = manager.createRxLock("test-retry-logic");
            
            // Test retry with immediate success (should succeed on first try)
            TestObserver<Boolean> retryObserver = rxLock.tryLockWithRetryRx(3, 100, TimeUnit.MILLISECONDS).test();
            retryObserver.await(5, TimeUnit.SECONDS);
            retryObserver.assertComplete();
            retryObserver.assertValue(true);
            
            assertTrue(rxLock.isHeldByCurrentThread(), "Lock should be held after retry success");
            
            // Cleanup
            rxLock.unlockRx().test().await();
        }
    }
    
    @Test
    public void testCombinedAsyncRxLock() throws Exception {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            AsyncRxRedlock combinedLock = manager.createAsyncRxLock("test-combined-lock");
            
            // Test CompletionStage interface
            Boolean asyncResult = combinedLock.tryLockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(asyncResult, "Should acquire lock via CompletionStage interface");
            
            // Test RxJava interface on same lock
            assertTrue(combinedLock.isHeldByCurrentThread(), "Lock should be held");
            
            // Test validity observable
            TestObserver<Long> validityObserver = combinedLock.validityObservable(200, TimeUnit.MILLISECONDS)
                .take(2)
                .test();
            
            validityObserver.await(1, TimeUnit.SECONDS);
            validityObserver.assertValueCount(2);
            
            // Test async unlock
            combinedLock.unlockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertFalse(combinedLock.isHeldByCurrentThread(), "Lock should not be held after unlock");
        }
    }
    
    @Test
    public void testConcurrentAsyncOperations() throws Exception {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            AsyncRedlock asyncLock1 = manager.createAsyncLock("concurrent-async-test");
            AsyncRedlock asyncLock2 = manager.createAsyncLock("concurrent-async-test"); // Same key
            
            // First lock should succeed
            Boolean acquired1 = asyncLock1.tryLockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(acquired1, "First async lock should succeed");
            
            // Second lock should fail (same resource)
            Boolean acquired2 = asyncLock2.tryLockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertFalse(acquired2, "Second async lock should fail for same resource");
            
            // Release first lock
            asyncLock1.unlockAsync().toCompletableFuture().get();
            
            // Now second lock should succeed
            Boolean acquired3 = asyncLock2.tryLockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(acquired3, "Second lock should succeed after first is released");
            
            // Cleanup
            asyncLock2.unlockAsync().toCompletableFuture().get();
        }
    }
}
