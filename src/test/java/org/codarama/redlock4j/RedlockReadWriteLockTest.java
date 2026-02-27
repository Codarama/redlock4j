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
import java.util.concurrent.locks.Lock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RedlockReadWriteLock using Mockito mocks.
 */
@ExtendWith(MockitoExtension.class)
public class RedlockReadWriteLockTest {

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

    // ========== Read Lock ==========

    @Test
    void shouldAcquireReadLockWhenNoWriter() throws RedisDriverException, InterruptedException {
        // No write lock exists
        when(mockDriver1.get(contains(":write"))).thenReturn(null);
        when(mockDriver2.get(contains(":write"))).thenReturn(null);
        when(mockDriver3.get(contains(":write"))).thenReturn(null);

        // Increment succeeds
        when(mockDriver1.incr(anyString())).thenReturn(1L);
        when(mockDriver2.incr(anyString())).thenReturn(1L);
        when(mockDriver3.incr(anyString())).thenReturn(1L);

        RedlockReadWriteLock rwLock = new RedlockReadWriteLock("test-rw", drivers, testConfig);
        Lock readLock = rwLock.readLock();

        boolean acquired = readLock.tryLock(1, TimeUnit.SECONDS);

        assertTrue(acquired);
        verify(mockDriver1, atLeastOnce()).incr(contains(":readers"));
    }

    @Test
    void shouldFailReadLockWhenWriterHoldsLock() throws RedisDriverException, InterruptedException {
        // Write lock exists on quorum
        when(mockDriver1.get(contains(":write"))).thenReturn("some-lock-value");
        when(mockDriver2.get(contains(":write"))).thenReturn("some-lock-value");
        when(mockDriver3.get(contains(":write"))).thenReturn(null);

        RedlockReadWriteLock rwLock = new RedlockReadWriteLock("test-rw", drivers, testConfig);
        Lock readLock = rwLock.readLock();

        boolean acquired = readLock.tryLock(100, TimeUnit.MILLISECONDS);

        assertFalse(acquired);
    }

    // ========== Write Lock ==========

    @Test
    void shouldAcquireWriteLockWhenNoReaders() throws RedisDriverException, InterruptedException {
        // No readers
        when(mockDriver1.get(contains(":readers"))).thenReturn(null);
        when(mockDriver2.get(contains(":readers"))).thenReturn(null);
        when(mockDriver3.get(contains(":readers"))).thenReturn(null);

        // Lock acquisition succeeds
        when(mockDriver1.setIfNotExists(contains(":write"), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(contains(":write"), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(contains(":write"), anyString(), anyLong())).thenReturn(true);

        RedlockReadWriteLock rwLock = new RedlockReadWriteLock("test-rw", drivers, testConfig);
        Lock writeLock = rwLock.writeLock();

        boolean acquired = writeLock.tryLock(1, TimeUnit.SECONDS);

        assertTrue(acquired);
    }

    @Test
    void shouldFailWriteLockWhenReadersExist() throws RedisDriverException, InterruptedException {
        // Active readers on quorum
        when(mockDriver1.get(contains(":readers"))).thenReturn("2");
        when(mockDriver2.get(contains(":readers"))).thenReturn("2");
        when(mockDriver3.get(contains(":readers"))).thenReturn(null);

        RedlockReadWriteLock rwLock = new RedlockReadWriteLock("test-rw", drivers, testConfig);
        Lock writeLock = rwLock.writeLock();

        boolean acquired = writeLock.tryLock(100, TimeUnit.MILLISECONDS);

        assertFalse(acquired);
    }

    // ========== Utility ==========

    @Test
    void shouldReturnSameLockInstances() {
        RedlockReadWriteLock rwLock = new RedlockReadWriteLock("test-rw", drivers, testConfig);

        Lock read1 = rwLock.readLock();
        Lock read2 = rwLock.readLock();
        Lock write1 = rwLock.writeLock();
        Lock write2 = rwLock.writeLock();

        assertSame(read1, read2);
        assertSame(write1, write2);
    }

    @Test
    void shouldThrowOnNewConditionForReadLock() {
        RedlockReadWriteLock rwLock = new RedlockReadWriteLock("test-rw", drivers, testConfig);
        assertThrows(UnsupportedOperationException.class, () -> rwLock.readLock().newCondition());
    }

    @Test
    void shouldThrowOnNewConditionForWriteLock() {
        RedlockReadWriteLock rwLock = new RedlockReadWriteLock("test-rw", drivers, testConfig);
        assertThrows(UnsupportedOperationException.class, () -> rwLock.writeLock().newCondition());
    }
}
