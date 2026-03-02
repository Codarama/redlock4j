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
 * Unit tests for RedlockCountDownLatch using Mockito mocks.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class RedlockCountDownLatchTest {

    @Mock
    private RedisDriver mockDriver1;

    @Mock
    private RedisDriver mockDriver2;

    @Mock
    private RedisDriver mockDriver3;

    private RedlockConfiguration testConfig;
    private List<RedisDriver> drivers;

    @BeforeEach
    void setUp() throws RedisDriverException {
        testConfig = RedlockConfiguration.builder().addRedisNode("localhost", 6379).addRedisNode("localhost", 6380)
                .addRedisNode("localhost", 6381).defaultLockTimeout(Duration.ofSeconds(30))
                .retryDelay(Duration.ofMillis(10)).maxRetryAttempts(3).lockAcquisitionTimeout(Duration.ofSeconds(10))
                .build();

        drivers = Arrays.asList(mockDriver1, mockDriver2, mockDriver3);

        lenient().when(mockDriver1.getIdentifier()).thenReturn("redis://localhost:6379");
        lenient().when(mockDriver2.getIdentifier()).thenReturn("redis://localhost:6380");
        lenient().when(mockDriver3.getIdentifier()).thenReturn("redis://localhost:6381");

        // Default setex for initialization (void method - use doNothing)
        lenient().doNothing().when(mockDriver1).setex(anyString(), anyString(), anyLong());
        lenient().doNothing().when(mockDriver2).setex(anyString(), anyString(), anyLong());
        lenient().doNothing().when(mockDriver3).setex(anyString(), anyString(), anyLong());
    }

    // ========== Validation ==========

    @Test
    void shouldRejectNegativeCount() {
        assertThrows(IllegalArgumentException.class, () -> new RedlockCountDownLatch("test", -1, drivers, testConfig));
    }

    @Test
    void shouldAllowZeroCount() throws RedisDriverException {
        // Zero is allowed
        RedlockCountDownLatch latch = new RedlockCountDownLatch("test", 0, drivers, testConfig);
        assertNotNull(latch);
    }

    // ========== getCount ==========

    @Test
    void shouldReturnCountFromQuorum() throws RedisDriverException, InterruptedException {
        when(mockDriver1.get(anyString())).thenReturn("5");
        when(mockDriver2.get(anyString())).thenReturn("5");
        when(mockDriver3.get(anyString())).thenReturn("5");

        RedlockCountDownLatch latch = new RedlockCountDownLatch("test", 5, drivers, testConfig);
        long count = latch.getCount();

        assertEquals(5, count);
    }

    // ========== countDown ==========

    @Test
    void shouldCallDecrAndPublishOnAllNodes() throws RedisDriverException, InterruptedException {
        when(mockDriver1.decrAndPublishIfZero(anyString(), anyString(), anyString())).thenReturn(4L);
        when(mockDriver2.decrAndPublishIfZero(anyString(), anyString(), anyString())).thenReturn(4L);
        when(mockDriver3.decrAndPublishIfZero(anyString(), anyString(), anyString())).thenReturn(4L);

        RedlockCountDownLatch latch = new RedlockCountDownLatch("test", 5, drivers, testConfig);
        latch.countDown();

        // Give async operations time to complete
        Thread.sleep(100);

        verify(mockDriver1, atLeastOnce()).decrAndPublishIfZero(eq("test"), eq("test:channel"), eq("zero"));
    }

    // ========== await ==========

    @Test
    void shouldReturnImmediatelyWhenCountIsZero() throws RedisDriverException, InterruptedException {
        // Use lenient stubs since get() may or may not be called depending on implementation
        lenient().when(mockDriver1.get(anyString())).thenReturn("0");
        lenient().when(mockDriver2.get(anyString())).thenReturn("0");
        lenient().when(mockDriver3.get(anyString())).thenReturn("0");

        RedlockCountDownLatch latch = new RedlockCountDownLatch("test", 0, drivers, testConfig);

        long start = System.currentTimeMillis();
        boolean result = latch.await(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result);
        assertTrue(elapsed < 1000, "Should return quickly when count is zero");
    }

    @Test
    void shouldTimeoutWhenCountNeverReachesZero() throws RedisDriverException, InterruptedException {
        when(mockDriver1.get(anyString())).thenReturn("5");
        when(mockDriver2.get(anyString())).thenReturn("5");
        when(mockDriver3.get(anyString())).thenReturn("5");

        RedlockCountDownLatch latch = new RedlockCountDownLatch("test", 5, drivers, testConfig);

        long start = System.currentTimeMillis();
        boolean result = latch.await(200, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result);
        assertTrue(elapsed >= 150 && elapsed < 1000, "Should timeout after ~200ms");
    }

    // ========== reset ==========

    @Test
    void shouldResetLatchCount() throws RedisDriverException {
        RedlockCountDownLatch latch = new RedlockCountDownLatch("test", 5, drivers, testConfig);
        latch.reset();

        // Verify del was called
        verify(mockDriver1, atLeastOnce()).del(eq("test"));
    }

    // ========== hasQueuedThreads ==========

    @Test
    void shouldReturnTrueWhenCountPositive() throws RedisDriverException {
        // Use lenient since quorum logic may not call all drivers
        lenient().when(mockDriver1.get(anyString())).thenReturn("3");
        lenient().when(mockDriver2.get(anyString())).thenReturn("3");
        lenient().when(mockDriver3.get(anyString())).thenReturn("3");

        RedlockCountDownLatch latch = new RedlockCountDownLatch("test", 3, drivers, testConfig);
        assertTrue(latch.hasQueuedThreads());
    }
}
