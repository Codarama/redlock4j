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
 * Unit tests for RedlockSemaphore using Mockito mocks.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class RedlockSemaphoreTest {

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
    void shouldRejectZeroPermits() {
        assertThrows(IllegalArgumentException.class, () -> new RedlockSemaphore("test", 0, drivers, testConfig));
    }

    @Test
    void shouldRejectNegativePermits() {
        assertThrows(IllegalArgumentException.class, () -> new RedlockSemaphore("test", -1, drivers, testConfig));
    }

    @Test
    void shouldRejectAcquiringMoreThanMaxPermits() {
        RedlockSemaphore semaphore = new RedlockSemaphore("test", 3, drivers, testConfig);
        assertThrows(IllegalArgumentException.class, () -> semaphore.tryAcquire(4, 1, TimeUnit.SECONDS));
    }

    @Test
    void shouldRejectAcquiringZeroPermits() {
        RedlockSemaphore semaphore = new RedlockSemaphore("test", 3, drivers, testConfig);
        assertThrows(IllegalArgumentException.class, () -> semaphore.tryAcquire(0, 1, TimeUnit.SECONDS));
    }

    // ========== Acquisition ==========

    @Test
    void shouldAcquirePermitWhenQuorumSucceeds() throws RedisDriverException, InterruptedException {
        // setIfNotExists succeeds on all nodes
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        RedlockSemaphore semaphore = new RedlockSemaphore("test", 5, drivers, testConfig);
        boolean acquired = semaphore.tryAcquire(1, TimeUnit.SECONDS);

        assertTrue(acquired);
        verify(mockDriver1, atLeastOnce()).setIfNotExists(contains(":permit:"), anyString(), anyLong());
    }

    @Test
    void shouldFailWhenQuorumNotReached() throws RedisDriverException, InterruptedException {
        // Only 1 node succeeds (need quorum of 2)
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        RedlockSemaphore semaphore = new RedlockSemaphore("test", 5, drivers, testConfig);
        boolean acquired = semaphore.tryAcquire(100, TimeUnit.MILLISECONDS);

        assertFalse(acquired);
    }

    // ========== Release ==========

    @Test
    void shouldReleasePermitAfterAcquisition() throws RedisDriverException, InterruptedException {
        setupSuccessfulAcquisition();

        RedlockSemaphore semaphore = new RedlockSemaphore("test", 5, drivers, testConfig);
        semaphore.tryAcquire(1, TimeUnit.SECONDS);
        semaphore.release();

        // Verify deleteIfValueMatches was called to release permit
        verify(mockDriver1, atLeastOnce()).deleteIfValueMatches(contains(":permit:"), anyString());
    }

    @Test
    void shouldHandleReleaseWithoutAcquisition() {
        RedlockSemaphore semaphore = new RedlockSemaphore("test", 5, drivers, testConfig);
        // Should not throw
        semaphore.release();
    }

    // ========== Available Permits ==========

    @Test
    void shouldReportMaxPermitsWhenNoStateTracked() {
        RedlockSemaphore semaphore = new RedlockSemaphore("test", 5, drivers, testConfig);
        // Current implementation returns maxPermits
        assertEquals(5, semaphore.availablePermits());
    }

    // ========== Multiple Permits ==========

    @Test
    void shouldAcquireMultiplePermits() throws RedisDriverException, InterruptedException {
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        RedlockSemaphore semaphore = new RedlockSemaphore("test", 5, drivers, testConfig);
        boolean acquired = semaphore.tryAcquire(3, 1, TimeUnit.SECONDS);

        assertTrue(acquired);
        // 3 permits, each on 3 nodes = at least 3 setIfNotExists calls per driver
        verify(mockDriver1, atLeast(3)).setIfNotExists(contains(":permit:"), anyString(), anyLong());
    }

    private void setupSuccessfulAcquisition() throws RedisDriverException {
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
    }
}
