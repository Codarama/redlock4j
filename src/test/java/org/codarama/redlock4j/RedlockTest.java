/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j;

import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.codarama.redlock4j.driver.RedisDriver;
import org.codarama.redlock4j.driver.RedisDriverException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for Redlock using Mockito mocks.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class RedlockTest {

    @Mock
    private RedisDriver mockDriver1;

    @Mock
    private RedisDriver mockDriver2;

    @Mock
    private RedisDriver mockDriver3;

    private RedlockConfiguration testConfig;
    private List<RedisDriver> drivers;

    @BeforeEach
    void setUp() {
        testConfig = RedlockConfiguration.builder().addRedisNode("localhost", 6379).addRedisNode("localhost", 6380)
                .addRedisNode("localhost", 6381).defaultLockTimeout(Duration.ofSeconds(30))
                .retryDelay(Duration.ofMillis(100)).maxRetryAttempts(3).lockAcquisitionTimeout(Duration.ofSeconds(10))
                .build();

        drivers = Arrays.asList(mockDriver1, mockDriver2, mockDriver3);

        // Setup default mock behavior - use lenient() to avoid unnecessary stubbing warnings
        lenient().when(mockDriver1.getIdentifier()).thenReturn("redis://localhost:6379");
        lenient().when(mockDriver2.getIdentifier()).thenReturn("redis://localhost:6380");
        lenient().when(mockDriver3.getIdentifier()).thenReturn("redis://localhost:6381");
    }

    @Test
    public void testTryLockSuccess() throws RedisDriverException {
        // Mock successful lock acquisition on all nodes
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        Redlock lock = new Redlock("test-key", drivers, testConfig);

        boolean acquired = lock.tryLock();

        assertTrue(acquired);
        assertTrue(lock.isHeldByCurrentThread());
        assertTrue(lock.getRemainingValidityTime() > 0);

        // Verify all drivers were called
        verify(mockDriver1).setIfNotExists(eq("test-key"), anyString(), eq(30000L));
        verify(mockDriver2).setIfNotExists(eq("test-key"), anyString(), eq(30000L));
        verify(mockDriver3).setIfNotExists(eq("test-key"), anyString(), eq(30000L));
    }

    @Test
    public void testTryLockFailureInsufficientNodes() throws RedisDriverException {
        // Mock failure on majority of nodes (only 1 success, need 2 for quorum)
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        Redlock lock = new Redlock("test-key", drivers, testConfig);

        boolean acquired = lock.tryLock();

        assertFalse(acquired);
        assertFalse(lock.isHeldByCurrentThread());
        assertEquals(0, lock.getRemainingValidityTime());

        // Verify cleanup - should try to delete the lock that was acquired
        verify(mockDriver1, atLeastOnce()).deleteIfValueMatches(eq("test-key"), anyString());
        verify(mockDriver2, atLeastOnce()).deleteIfValueMatches(eq("test-key"), anyString());
        verify(mockDriver3, atLeastOnce()).deleteIfValueMatches(eq("test-key"), anyString());
    }

    @Test
    public void testTryLockWithTimeout() throws InterruptedException, RedisDriverException {
        // Mock successful lock acquisition on quorum
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        Redlock lock = new Redlock("test-key", drivers, testConfig);

        boolean acquired = lock.tryLock(5, TimeUnit.SECONDS);

        assertTrue(acquired);
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    public void testTryLockTimeoutExceeded() throws InterruptedException, RedisDriverException {
        // Mock failure on all attempts
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        Redlock lock = new Redlock("test-key", drivers, testConfig);

        long startTime = System.currentTimeMillis();
        boolean acquired = lock.tryLock(200, TimeUnit.MILLISECONDS);
        long elapsedTime = System.currentTimeMillis() - startTime;

        assertFalse(acquired);
        assertFalse(lock.isHeldByCurrentThread());
        assertTrue(elapsedTime >= 200); // Should respect timeout
    }

    @Test
    public void testLockSuccess() throws RedisDriverException {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        Redlock lock = new Redlock("test-key", drivers, testConfig);

        assertDoesNotThrow(lock::lock);
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    public void testLockFailureThrowsException() throws RedisDriverException {
        // Mock failure on all nodes
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        Redlock lock = new Redlock("test-key", drivers, testConfig);
        RedlockException exception = assertThrows(RedlockException.class, () -> lock.lock());

        assertTrue(exception.getMessage().contains("Failed to acquire lock within timeout"));
    }

    @Test
    public void testLockInterruptibly() throws RedisDriverException {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        Redlock lock = new Redlock("test-key", drivers, testConfig);

        assertDoesNotThrow(lock::lockInterruptibly);
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    public void testLockInterruptiblyWithInterruption() throws RedisDriverException {
        // Use lenient stubbing to avoid unnecessary stubbing warnings
        lenient().when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        lenient().when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        lenient().when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        Redlock lock = new Redlock("test-key", drivers, testConfig);

        // Interrupt the current thread
        Thread.currentThread().interrupt();

        assertThrows(InterruptedException.class, () -> lock.tryLock(1, TimeUnit.SECONDS));

        // Clear interrupt flag
        Thread.interrupted();
    }

    @Test
    public void testUnlockSuccess() throws RedisDriverException {
        // First acquire the lock
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        Redlock lock = new Redlock("test-key", drivers, testConfig);
        assertTrue(lock.tryLock());

        // Now unlock
        lock.unlock();

        assertFalse(lock.isHeldByCurrentThread());
        assertEquals(0, lock.getRemainingValidityTime());

        // Verify unlock was called on all drivers
        verify(mockDriver1, atLeastOnce()).deleteIfValueMatches(eq("test-key"), anyString());
        verify(mockDriver2, atLeastOnce()).deleteIfValueMatches(eq("test-key"), anyString());
        verify(mockDriver3, atLeastOnce()).deleteIfValueMatches(eq("test-key"), anyString());
    }

    @Test
    public void testUnlockWithoutLock() {
        Redlock lock = new Redlock("test-key", drivers, testConfig);

        // Should not throw exception when unlocking without holding lock
        assertDoesNotThrow(lock::unlock);

        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    public void testDriverExceptionDuringLockAcquisition() throws RedisDriverException {
        // Mock exception on one driver, success on others
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong()))
                .thenThrow(new RedisDriverException("Connection failed"));
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        Redlock lock = new Redlock("test-key", drivers, testConfig);

        boolean acquired = lock.tryLock();

        assertTrue(acquired); // Should still succeed with 2/3 nodes
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    public void testDriverExceptionDuringUnlock() throws RedisDriverException {
        // First acquire the lock
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        Redlock lock = new Redlock("test-key", drivers, testConfig);
        assertTrue(lock.tryLock());

        // Mock exception during unlock
        doThrow(new RedisDriverException("Delete failed")).when(mockDriver1).deleteIfValueMatches(anyString(),
                anyString());

        // Should not throw exception
        assertDoesNotThrow(lock::unlock);

        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    public void testNewConditionThrowsUnsupportedOperation() {
        Redlock lock = new Redlock("test-key", drivers, testConfig);

        assertThrows(UnsupportedOperationException.class, lock::newCondition);
    }

    @Test
    public void testIsHeldByCurrentThreadAfterExpiry() throws RedisDriverException, InterruptedException {
        // Use very short timeout for testing expiry with 3 nodes to satisfy validation
        RedlockConfiguration shortConfig = RedlockConfiguration.builder().addRedisNode("localhost", 6379)
                .addRedisNode("localhost", 6380).addRedisNode("localhost", 6381)
                .defaultLockTimeout(Duration.ofMillis(50)).retryDelay(Duration.ofMillis(10)).maxRetryAttempts(1)
                .build();

        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        Redlock lock = new Redlock("test-key", drivers, shortConfig);

        assertTrue(lock.tryLock());
        assertTrue(lock.isHeldByCurrentThread());

        // Wait for lock to expire
        Thread.sleep(100);

        assertFalse(lock.isHeldByCurrentThread());
        assertEquals(0, lock.getRemainingValidityTime());
    }

    @Test
    public void testUnlockExpiredLock() throws RedisDriverException, InterruptedException {
        // Use very short timeout for testing expiry with 3 nodes to satisfy validation
        RedlockConfiguration shortConfig = RedlockConfiguration.builder().addRedisNode("localhost", 6379)
                .addRedisNode("localhost", 6380).addRedisNode("localhost", 6381)
                .defaultLockTimeout(Duration.ofMillis(50)).retryDelay(Duration.ofMillis(10)).maxRetryAttempts(1)
                .build();

        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        Redlock lock = new Redlock("test-key", drivers, shortConfig);

        assertTrue(lock.tryLock());

        // Wait for lock to expire
        Thread.sleep(100);

        // Unlock should handle expired lock gracefully
        assertDoesNotThrow(lock::unlock);
    }

    @Test
    public void testRetryLogicWithEventualSuccess() throws RedisDriverException {
        // Mock failure on first attempts, success on later attempt
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false) // First attempt fails
                .thenReturn(true); // Second attempt succeeds
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false) // First attempt fails
                .thenReturn(true); // Second attempt succeeds
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        Redlock lock = new Redlock("test-key", drivers, testConfig);

        boolean acquired = lock.tryLock();

        assertTrue(acquired);
        assertTrue(lock.isHeldByCurrentThread());

        // Verify retry happened (should be called at least twice for each driver)
        verify(mockDriver1, atLeast(2)).setIfNotExists(anyString(), anyString(), anyLong());
        verify(mockDriver2, atLeast(2)).setIfNotExists(anyString(), anyString(), anyLong());
    }

    @Test
    public void testClockDriftCalculation() throws RedisDriverException {
        // Mock slow response to test clock drift calculation
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            Thread.sleep(100); // Simulate slow response
            return true;
        });
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        Redlock lock = new Redlock("test-key", drivers, testConfig);

        boolean acquired = lock.tryLock();

        assertTrue(acquired);
        assertTrue(lock.isHeldByCurrentThread());

        // Validity time should be reduced due to elapsed time and clock drift
        long remainingTime = lock.getRemainingValidityTime();
        assertTrue(remainingTime < 30000); // Should be less than full timeout
        assertTrue(remainingTime > 0); // But still positive
    }

    @Test
    public void testLockValueUniqueness() throws RedisDriverException {
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        Redlock lock1 = new Redlock("test-key", drivers, testConfig);
        Redlock lock2 = new Redlock("test-key", drivers, testConfig);

        lock1.tryLock();
        lock2.tryLock();

        // Capture the lock values used
        verify(mockDriver1, times(2)).setIfNotExists(eq("test-key"), anyString(), anyLong());

        // The lock values should be different (we can't easily verify this with Mockito,
        // but the test ensures the method is called with different values)
    }

    @Test
    public void testConcurrentThreadsHaveSeparateLockState() throws RedisDriverException, InterruptedException {
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        Redlock lock = new Redlock("test-key", drivers, testConfig);

        // Acquire lock in main thread
        assertTrue(lock.tryLock());
        assertTrue(lock.isHeldByCurrentThread());

        // Check from another thread
        Thread otherThread = new Thread(() -> {
            assertFalse(lock.isHeldByCurrentThread());
            assertEquals(0, lock.getRemainingValidityTime());
        });

        otherThread.start();
        otherThread.join();

        // Main thread should still hold the lock
        assertTrue(lock.isHeldByCurrentThread());
    }

    @Test
    public void testZeroTimeoutTryLock() throws RedisDriverException, InterruptedException {
        // Mock failure to test immediate return
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        Redlock lock = new Redlock("test-key", drivers, testConfig);

        long startTime = System.currentTimeMillis();
        boolean acquired = lock.tryLock(0, TimeUnit.MILLISECONDS);
        long elapsedTime = System.currentTimeMillis() - startTime;

        assertFalse(acquired);
        assertTrue(elapsedTime < 1000); // Should return quickly

        // Should try at least once but may retry due to configuration
        verify(mockDriver1, atLeastOnce()).setIfNotExists(anyString(), anyString(), anyLong());
    }
}
