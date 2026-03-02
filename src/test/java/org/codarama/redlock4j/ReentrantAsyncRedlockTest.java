/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j;

import org.codarama.redlock4j.async.AsyncRedlockImpl;
import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.codarama.redlock4j.driver.RedisDriver;
import org.codarama.redlock4j.driver.RedisDriverException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for reentrant functionality of AsyncRedlockImpl using Mockito mocks.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class ReentrantAsyncRedlockTest {

    @Mock
    private RedisDriver mockDriver1;

    @Mock
    private RedisDriver mockDriver2;

    @Mock
    private RedisDriver mockDriver3;

    private RedlockConfiguration testConfig;
    private List<RedisDriver> drivers;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;

    @BeforeEach
    void setUp() {
        testConfig = RedlockConfiguration.builder().addRedisNode("localhost", 6379).addRedisNode("localhost", 6380)
                .addRedisNode("localhost", 6381).defaultLockTimeout(Duration.ofSeconds(30))
                .retryDelay(Duration.ofMillis(100)).maxRetryAttempts(3).lockAcquisitionTimeout(Duration.ofSeconds(10))
                .build();

        drivers = Arrays.asList(mockDriver1, mockDriver2, mockDriver3);
        executorService = Executors.newFixedThreadPool(4);
        scheduledExecutorService = Executors.newScheduledThreadPool(2);

        // Setup default mock behavior
        lenient().when(mockDriver1.getIdentifier()).thenReturn("redis://localhost:6379");
        lenient().when(mockDriver2.getIdentifier()).thenReturn("redis://localhost:6380");
        lenient().when(mockDriver3.getIdentifier()).thenReturn("redis://localhost:6381");
    }

    @AfterEach
    void tearDown() {
        executorService.shutdown();
        scheduledExecutorService.shutdown();
    }

    @Test
    public void testBasicAsyncReentrancy() throws Exception {
        // Mock successful lock acquisition on quorum
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        AsyncRedlockImpl lock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService,
                scheduledExecutorService);

        // First acquisition
        CompletionStage<Boolean> firstAcquisition = lock.tryLockAsync();
        assertTrue(firstAcquisition.toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(1, lock.getHoldCount());

        // Second acquisition (reentrant)
        CompletionStage<Boolean> secondAcquisition = lock.tryLockAsync();
        assertTrue(secondAcquisition.toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(2, lock.getHoldCount());

        // Third acquisition (reentrant)
        CompletionStage<Boolean> thirdAcquisition = lock.tryLockAsync();
        assertTrue(thirdAcquisition.toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(3, lock.getHoldCount());

        // First unlock
        CompletionStage<Void> firstUnlock = lock.unlockAsync();
        firstUnlock.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(2, lock.getHoldCount());

        // Second unlock
        CompletionStage<Void> secondUnlock = lock.unlockAsync();
        secondUnlock.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(1, lock.getHoldCount());

        // Final unlock
        CompletionStage<Void> finalUnlock = lock.unlockAsync();
        finalUnlock.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(lock.isHeldByCurrentThread());
        assertEquals(0, lock.getHoldCount());

        // Verify Redis operations were called only once (for initial acquisition)
        verify(mockDriver1, times(1)).setIfNotExists(anyString(), anyString(), anyLong());
        verify(mockDriver2, times(1)).setIfNotExists(anyString(), anyString(), anyLong());
        verify(mockDriver3, times(1)).setIfNotExists(anyString(), anyString(), anyLong());

        // Verify unlock was called only once (for final release)
        verify(mockDriver1, times(1)).deleteIfValueMatches(anyString(), anyString());
        verify(mockDriver2, times(1)).deleteIfValueMatches(anyString(), anyString());
        verify(mockDriver3, times(1)).deleteIfValueMatches(anyString(), anyString());
    }

    @Test
    public void testAsyncReentrantLockWithTimeout() throws Exception {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        AsyncRedlockImpl lock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService,
                scheduledExecutorService);

        // First acquisition
        CompletionStage<Boolean> firstAcquisition = lock.tryLockAsync(Duration.ofSeconds(5));
        assertTrue(firstAcquisition.toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(1, lock.getHoldCount());

        // Reentrant acquisition with timeout
        CompletionStage<Boolean> secondAcquisition = lock.tryLockAsync(Duration.ofSeconds(5));
        assertTrue(secondAcquisition.toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(2, lock.getHoldCount());

        // Unlock both
        lock.unlockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, lock.getHoldCount());
        lock.unlockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(0, lock.getHoldCount());

        // Verify Redis operations were called only once
        verify(mockDriver1, times(1)).setIfNotExists(anyString(), anyString(), anyLong());
    }

    @Test
    public void testAsyncReentrantLockWithLockAsync() throws Exception {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        AsyncRedlockImpl lock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService,
                scheduledExecutorService);

        // First acquisition using lockAsync()
        CompletionStage<Void> firstLock = lock.lockAsync();
        assertDoesNotThrow(() -> firstLock.toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(1, lock.getHoldCount());

        // Reentrant acquisition using tryLockAsync()
        CompletionStage<Boolean> secondLock = lock.tryLockAsync();
        assertTrue(secondLock.toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(2, lock.getHoldCount());

        // Unlock both
        lock.unlockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, lock.getHoldCount());
        lock.unlockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(0, lock.getHoldCount());
    }

    @Test
    public void testAsyncUnlockWithoutLockDoesNotThrow() throws Exception {
        AsyncRedlockImpl lock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService,
                scheduledExecutorService);

        // Should not throw exception when unlocking without holding lock
        CompletionStage<Void> unlock = lock.unlockAsync();
        assertDoesNotThrow(() -> unlock.toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(0, lock.getHoldCount());
    }

    @Test
    public void testRxReentrantLock() throws Exception {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        AsyncRedlockImpl lock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService,
                scheduledExecutorService);

        // First acquisition using RxJava
        Boolean firstResult = lock.tryLockRx().blockingGet();
        assertTrue(firstResult);
        assertEquals(1, lock.getHoldCount());

        // Reentrant acquisition using RxJava
        Boolean secondResult = lock.tryLockRx().blockingGet();
        assertTrue(secondResult);
        assertEquals(2, lock.getHoldCount());

        // Unlock both using RxJava
        assertDoesNotThrow(() -> lock.unlockRx().blockingAwait());
        assertEquals(1, lock.getHoldCount());
        assertDoesNotThrow(() -> lock.unlockRx().blockingAwait());
        assertEquals(0, lock.getHoldCount());

        // Verify Redis operations were called only once
        verify(mockDriver1, times(1)).setIfNotExists(anyString(), anyString(), anyLong());
    }

    @Test
    public void testAsyncReentrantLockValidityTime() throws Exception {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        AsyncRedlockImpl lock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService,
                scheduledExecutorService);

        // Acquire lock
        assertTrue(lock.tryLockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS));
        long firstValidityTime = lock.getRemainingValidityTime();
        assertTrue(firstValidityTime > 0);

        // Reentrant acquisition should not change validity time significantly
        assertTrue(lock.tryLockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS));
        long secondValidityTime = lock.getRemainingValidityTime();
        assertTrue(secondValidityTime > 0);
        // Should be approximately the same (allowing for small time differences)
        assertTrue(Math.abs(firstValidityTime - secondValidityTime) < 1000);

        // Unlock both
        lock.unlockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(lock.getRemainingValidityTime() > 0); // Still valid after first unlock

        lock.unlockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(0, lock.getRemainingValidityTime()); // Should be 0 after final unlock
    }

    @Test
    public void testAsyncReentrantLockKey() throws Exception {
        AsyncRedlockImpl lock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService,
                scheduledExecutorService);

        assertEquals("test-key", lock.getLockKey());

        // Lock key should remain the same regardless of hold count
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        assertTrue(lock.tryLockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals("test-key", lock.getLockKey());

        assertTrue(lock.tryLockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals("test-key", lock.getLockKey());

        lock.unlockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        lock.unlockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
