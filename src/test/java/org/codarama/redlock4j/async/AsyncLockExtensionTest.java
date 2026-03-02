/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.async;

import io.reactivex.rxjava3.observers.TestObserver;
import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.codarama.redlock4j.driver.RedisDriver;
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
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for async lock extension functionality.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class AsyncLockExtensionTest {

    @Mock
    private RedisDriver mockDriver1;

    @Mock
    private RedisDriver mockDriver2;

    @Mock
    private RedisDriver mockDriver3;

    private RedlockConfiguration testConfig;
    private List<RedisDriver> drivers;
    private AsyncRedlockImpl asyncLock;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;

    @BeforeEach
    void setUp() {
        testConfig = RedlockConfiguration.builder().addRedisNode("localhost", 6379).addRedisNode("localhost", 6380)
                .addRedisNode("localhost", 6381).defaultLockTimeout(Duration.ofSeconds(30))
                .retryDelay(Duration.ofMillis(100)).maxRetryAttempts(3).lockAcquisitionTimeout(Duration.ofSeconds(10))
                .build();

        drivers = Arrays.asList(mockDriver1, mockDriver2, mockDriver3);
        executorService = Executors.newFixedThreadPool(2);
        scheduledExecutorService = Executors.newScheduledThreadPool(1);

        // Setup default mock behavior
        lenient().when(mockDriver1.getIdentifier()).thenReturn("redis://localhost:6379");
        lenient().when(mockDriver2.getIdentifier()).thenReturn("redis://localhost:6380");
        lenient().when(mockDriver3.getIdentifier()).thenReturn("redis://localhost:6381");
    }

    @AfterEach
    void tearDown() {
        if (executorService != null) {
            executorService.shutdown();
        }
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdown();
        }
    }

    @Test
    public void testExtendAsyncSuccess() throws Exception {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        // Mock successful extension on all nodes
        when(mockDriver1.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);

        asyncLock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService, scheduledExecutorService);

        // Acquire lock
        Boolean acquired = asyncLock.tryLockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(acquired);

        long initialValidity = asyncLock.getRemainingValidityTime();

        // Extend lock
        Boolean extended = asyncLock.extendAsync(Duration.ofSeconds(10)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(extended);
        assertTrue(asyncLock.isHeldByCurrentThread());

        // Validity time should be greater after extension
        long newValidity = asyncLock.getRemainingValidityTime();
        assertTrue(newValidity > initialValidity,
                "New validity (" + newValidity + ") should be greater than initial (" + initialValidity + ")");

        // Verify setIfValueMatches was called on all drivers
        verify(mockDriver1).setIfValueMatches(eq("test-key"), anyString(), anyString(), eq(40000L));
        verify(mockDriver2).setIfValueMatches(eq("test-key"), anyString(), anyString(), eq(40000L));
        verify(mockDriver3).setIfValueMatches(eq("test-key"), anyString(), anyString(), eq(40000L));
    }

    @Test
    public void testExtendAsyncWithQuorum() throws Exception {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        // Mock extension succeeds on quorum (2 out of 3)
        when(mockDriver1.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(false);

        asyncLock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService, scheduledExecutorService);

        // Acquire lock
        Boolean acquired = asyncLock.tryLockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(acquired);

        // Extend lock - should succeed with quorum
        Boolean extended = asyncLock.extendAsync(Duration.ofSeconds(10)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(extended);
        assertTrue(asyncLock.isHeldByCurrentThread());
    }

    @Test
    public void testExtendAsyncFailsWithoutQuorum() throws Exception {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        // Mock extension fails on majority (only 1 out of 3 succeeds)
        when(mockDriver1.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(false);
        when(mockDriver3.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(false);

        asyncLock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService, scheduledExecutorService);

        // Acquire lock
        Boolean acquired = asyncLock.tryLockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(acquired);

        // Extend lock - should fail without quorum
        Boolean extended = asyncLock.extendAsync(Duration.ofSeconds(10)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertFalse(extended);
        // Lock should still be held (original lock not affected)
        assertTrue(asyncLock.isHeldByCurrentThread());
    }

    @Test
    public void testExtendAsyncWithoutHoldingLock() throws Exception {
        asyncLock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService, scheduledExecutorService);

        // Try to extend without holding lock
        Boolean extended = asyncLock.extendAsync(Duration.ofSeconds(10)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertFalse(extended);
        assertFalse(asyncLock.isHeldByCurrentThread());
    }

    @Test
    public void testExtendAsyncWithNegativeTime() throws Exception {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        asyncLock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService, scheduledExecutorService);

        // Acquire lock
        Boolean acquired = asyncLock.tryLockAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(acquired);

        // Try to extend with negative time
        CompletionStage<Boolean> future = asyncLock.extendAsync(Duration.ofSeconds(-1));

        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> future.toCompletableFuture().get(5, TimeUnit.SECONDS));

        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
    }

    @Test
    public void testExtendRxSuccess() throws Exception {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        // Mock successful extension on all nodes
        when(mockDriver1.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);

        asyncLock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService, scheduledExecutorService);

        // Acquire lock
        TestObserver<Boolean> acquireObserver = asyncLock.tryLockRx().test();
        acquireObserver.await(5, TimeUnit.SECONDS);
        acquireObserver.assertValue(true);

        // Extend lock
        TestObserver<Boolean> extendObserver = asyncLock.extendRx(Duration.ofSeconds(10)).test();
        extendObserver.await(5, TimeUnit.SECONDS);
        extendObserver.assertValue(true);
        extendObserver.assertComplete();

        assertTrue(asyncLock.isHeldByCurrentThread());
    }

    @Test
    public void testExtendRxWithQuorum() throws Exception {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        // Mock extension succeeds on quorum (2 out of 3)
        when(mockDriver1.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(false);

        asyncLock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService, scheduledExecutorService);

        // Acquire lock
        TestObserver<Boolean> acquireObserver = asyncLock.tryLockRx().test();
        acquireObserver.await(5, TimeUnit.SECONDS);
        acquireObserver.assertValue(true);

        // Extend lock - should succeed with quorum
        TestObserver<Boolean> extendObserver = asyncLock.extendRx(Duration.ofSeconds(10)).test();
        extendObserver.await(5, TimeUnit.SECONDS);
        extendObserver.assertValue(true);
        extendObserver.assertComplete();

        assertTrue(asyncLock.isHeldByCurrentThread());
    }

    @Test
    public void testExtendRxWithoutHoldingLock() throws Exception {
        asyncLock = new AsyncRedlockImpl("test-key", drivers, testConfig, executorService, scheduledExecutorService);

        // Try to extend without holding lock
        TestObserver<Boolean> extendObserver = asyncLock.extendRx(Duration.ofSeconds(10)).test();
        extendObserver.await(5, TimeUnit.SECONDS);
        extendObserver.assertValue(false);
        extendObserver.assertComplete();

        assertFalse(asyncLock.isHeldByCurrentThread());
    }
}
