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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FairLock using Mockito mocks.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class FairLockTest {

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

    // ========== Basic Acquisition ==========

    @Test
    void shouldAcquireLockWhenAtFrontOfQueueAndQuorumSucceeds() throws RedisDriverException, InterruptedException {
        setupSuccessfulAcquisition();

        FairLock lock = new FairLock("test-fair", drivers, testConfig);
        boolean acquired = lock.tryLock(1, TimeUnit.SECONDS);

        assertTrue(acquired);
        assertTrue(lock.isHeldByCurrentThread());
        assertTrue(lock.getHoldCount() > 0);
    }

    @Test
    void shouldFailWhenNotAtFrontOfQueue() throws RedisDriverException, InterruptedException {
        // Mock: add to queue succeeds
        when(mockDriver1.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        when(mockDriver2.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        when(mockDriver3.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);

        // Mock: someone else is at front
        when(mockDriver1.zRange(anyString(), eq(0L), eq(0L))).thenReturn(Collections.singletonList("other-token"));
        when(mockDriver2.zRange(anyString(), eq(0L), eq(0L))).thenReturn(Collections.singletonList("other-token"));
        when(mockDriver3.zRange(anyString(), eq(0L), eq(0L))).thenReturn(Collections.singletonList("other-token"));

        FairLock lock = new FairLock("test-fair", drivers, testConfig);

        boolean acquired = lock.tryLock(100, TimeUnit.MILLISECONDS);

        assertFalse(acquired);
        assertFalse(lock.isHeldByCurrentThread());
    }

    // ========== Reentrancy ==========

    @Test
    void shouldSupportReentrantAcquisition() throws RedisDriverException, InterruptedException {
        setupSuccessfulAcquisition();

        FairLock lock = new FairLock("test-reentrant", drivers, testConfig);

        assertTrue(lock.tryLock(1, TimeUnit.SECONDS));
        assertEquals(1, lock.getHoldCount());

        // Reentrant acquisition should succeed without Redis calls
        assertTrue(lock.tryLock(1, TimeUnit.SECONDS));
        assertEquals(2, lock.getHoldCount());

        lock.unlock();
        assertEquals(1, lock.getHoldCount());
        assertTrue(lock.isHeldByCurrentThread());

        lock.unlock();
        assertEquals(0, lock.getHoldCount());
        assertFalse(lock.isHeldByCurrentThread());
    }

    // ========== Utility Methods ==========

    @Test
    void shouldReportZeroValidityTimeWhenNotHeld() {
        FairLock lock = new FairLock("test-validity", drivers, testConfig);
        assertEquals(0, lock.getRemainingValidityTime());
    }

    @Test
    void shouldThrowOnNewCondition() {
        FairLock lock = new FairLock("test-condition", drivers, testConfig);
        assertThrows(UnsupportedOperationException.class, lock::newCondition);
    }

    private void setupSuccessfulAcquisition() throws RedisDriverException {
        when(mockDriver1.zAdd(anyString(), anyDouble(), anyString())).thenAnswer(inv -> {
            String token = inv.getArgument(2);
            lenient().when(mockDriver1.zRange(anyString(), eq(0L), eq(0L)))
                    .thenReturn(Collections.singletonList(token));
            lenient().when(mockDriver2.zRange(anyString(), eq(0L), eq(0L)))
                    .thenReturn(Collections.singletonList(token));
            lenient().when(mockDriver3.zRange(anyString(), eq(0L), eq(0L)))
                    .thenReturn(Collections.singletonList(token));
            return true;
        });
        when(mockDriver2.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        when(mockDriver3.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
    }
}
