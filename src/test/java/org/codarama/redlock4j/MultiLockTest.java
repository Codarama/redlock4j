/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j;

import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.codarama.redlock4j.driver.RedisDriver;
import org.codarama.redlock4j.driver.RedisDriverException;
import org.junit.jupiter.api.BeforeEach;
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
 * Unit tests for MultiLock using Mockito mocks.
 */
@ExtendWith(MockitoExtension.class)
public class MultiLockTest {

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
                .retryDelay(Duration.ofMillis(10)).maxRetryAttempts(3).lockAcquisitionTimeout(Duration.ofSeconds(10))
                .build();

        drivers = Arrays.asList(mockDriver1, mockDriver2, mockDriver3);

        lenient().when(mockDriver1.getIdentifier()).thenReturn("redis://localhost:6379");
        lenient().when(mockDriver2.getIdentifier()).thenReturn("redis://localhost:6380");
        lenient().when(mockDriver3.getIdentifier()).thenReturn("redis://localhost:6381");
    }

    // ========== Validation ==========

    @Test
    void shouldRejectNullKeyList() {
        assertThrows(IllegalArgumentException.class, () -> new MultiLock(null, drivers, testConfig));
    }

    @Test
    void shouldRejectEmptyKeyList() {
        assertThrows(IllegalArgumentException.class, () -> new MultiLock(Arrays.asList(), drivers, testConfig));
    }

    // ========== Acquisition ==========

    @Test
    void shouldAcquireAllLocksWhenQuorumSucceeds() throws RedisDriverException, InterruptedException {
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        MultiLock lock = new MultiLock(Arrays.asList("key1", "key2", "key3"), drivers, testConfig);
        boolean acquired = lock.tryLock(1, TimeUnit.SECONDS);

        assertTrue(acquired);

        // Verify all keys were locked on all drivers
        verify(mockDriver1, times(3)).setIfNotExists(anyString(), anyString(), eq(30000L));
        verify(mockDriver2, times(3)).setIfNotExists(anyString(), anyString(), eq(30000L));
    }

    @Test
    void shouldRollbackOnPartialFailure() throws RedisDriverException, InterruptedException {
        // First key succeeds, second fails on driver1
        when(mockDriver1.setIfNotExists(eq("key1"), anyString(), anyLong())).thenReturn(true);
        when(mockDriver1.setIfNotExists(eq("key2"), anyString(), anyLong())).thenReturn(false);
        // All fail on driver2 and driver3 for simplicity
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        MultiLock lock = new MultiLock(Arrays.asList("key1", "key2"), drivers, testConfig);
        boolean acquired = lock.tryLock(100, TimeUnit.MILLISECONDS);

        assertFalse(acquired);

        // Verify rollback was attempted for key1 on driver1
        verify(mockDriver1, atLeastOnce()).deleteIfValueMatches(eq("key1"), anyString());
    }

    // ========== Key Ordering ==========

    @Test
    void shouldSortKeysToPreventDeadlock() throws RedisDriverException, InterruptedException {
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        // Pass keys in reverse order
        MultiLock lock = new MultiLock(Arrays.asList("z", "a", "m"), drivers, testConfig);
        lock.tryLock(1, TimeUnit.SECONDS);

        // Verify keys are acquired in sorted order: a, m, z
        org.mockito.InOrder order = inOrder(mockDriver1);
        order.verify(mockDriver1).setIfNotExists(eq("a"), anyString(), anyLong());
        order.verify(mockDriver1).setIfNotExists(eq("m"), anyString(), anyLong());
        order.verify(mockDriver1).setIfNotExists(eq("z"), anyString(), anyLong());
    }

    @Test
    void shouldDeduplicateKeys() throws RedisDriverException, InterruptedException {
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        // Pass duplicate keys
        MultiLock lock = new MultiLock(Arrays.asList("key1", "key1", "key2"), drivers, testConfig);
        lock.tryLock(1, TimeUnit.SECONDS);

        // Should only lock 2 unique keys
        verify(mockDriver1, times(2)).setIfNotExists(anyString(), anyString(), anyLong());
    }

    // ========== Utility ==========

    @Test
    void shouldThrowOnNewCondition() {
        MultiLock lock = new MultiLock(Arrays.asList("key1"), drivers, testConfig);
        assertThrows(UnsupportedOperationException.class, lock::newCondition);
    }
}
