/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.async;

import io.reactivex.rxjava3.observers.TestObserver;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RxRedlock reactive API.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class RxRedlockTest {

    @Mock
    private RedisDriver mockDriver1;

    @Mock
    private RedisDriver mockDriver2;

    @Mock
    private RedisDriver mockDriver3;

    private RedlockConfiguration testConfig;
    private List<RedisDriver> drivers;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        testConfig = RedlockConfiguration.builder().addRedisNode("localhost", 6379).addRedisNode("localhost", 6380)
                .addRedisNode("localhost", 6381).defaultLockTimeout(Duration.ofSeconds(30))
                .retryDelay(Duration.ofMillis(10)).maxRetryAttempts(3).lockAcquisitionTimeout(Duration.ofSeconds(5))
                .build();

        drivers = Arrays.asList(mockDriver1, mockDriver2, mockDriver3);
        scheduler = Executors.newSingleThreadScheduledExecutor();

        lenient().when(mockDriver1.getIdentifier()).thenReturn("redis://localhost:6379");
        lenient().when(mockDriver2.getIdentifier()).thenReturn("redis://localhost:6380");
        lenient().when(mockDriver3.getIdentifier()).thenReturn("redis://localhost:6381");
    }

    // ========== tryLockRx ==========

    @Test
    void tryLockRxShouldEmitTrueOnSuccess() throws RedisDriverException {
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        RxRedlock rxLock = new AsyncRedlockImpl("test-rx", drivers, testConfig, Executors.newCachedThreadPool(),
                scheduler);

        TestObserver<Boolean> observer = rxLock.tryLockRx().test();
        observer.awaitDone(5, TimeUnit.SECONDS);

        observer.assertValue(true);
        observer.assertComplete();
    }

    @Test
    void tryLockRxShouldEmitFalseOnFailure() throws RedisDriverException {
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        RxRedlock rxLock = new AsyncRedlockImpl("test-rx", drivers, testConfig, Executors.newCachedThreadPool(),
                scheduler);

        TestObserver<Boolean> observer = rxLock.tryLockRx().test();
        observer.awaitDone(5, TimeUnit.SECONDS);

        observer.assertValue(false);
        observer.assertComplete();
    }

    // ========== lockStateObservable ==========

    @Test
    void lockStateObservableShouldEmitInitialState() {
        RxRedlock rxLock = new AsyncRedlockImpl("test-rx", drivers, testConfig, Executors.newCachedThreadPool(),
                scheduler);

        TestObserver<RxRedlock.LockState> observer = rxLock.lockStateObservable().test();

        observer.assertValue(RxRedlock.LockState.RELEASED);
    }

    // ========== Utility Methods ==========

    @Test
    void shouldReturnLockKey() {
        RxRedlock rxLock = new AsyncRedlockImpl("my-lock-key", drivers, testConfig, Executors.newCachedThreadPool(),
                scheduler);

        assertEquals("my-lock-key", rxLock.getLockKey());
    }

    @Test
    void shouldReturnZeroValidityTimeWhenNotHeld() {
        RxRedlock rxLock = new AsyncRedlockImpl("test-rx", drivers, testConfig, Executors.newCachedThreadPool(),
                scheduler);

        assertEquals(0, rxLock.getRemainingValidityTime());
    }

    @Test
    void shouldReturnFalseForIsHeldWhenNotAcquired() {
        RxRedlock rxLock = new AsyncRedlockImpl("test-rx", drivers, testConfig, Executors.newCachedThreadPool(),
                scheduler);

        assertFalse(rxLock.isHeldByCurrentThread());
    }

    @Test
    void shouldReturnZeroHoldCountWhenNotHeld() {
        RxRedlock rxLock = new AsyncRedlockImpl("test-rx", drivers, testConfig, Executors.newCachedThreadPool(),
                scheduler);

        assertEquals(0, rxLock.getHoldCount());
    }

    // ========== tryLockRx with timeout ==========

    @Test
    void tryLockRxWithTimeoutShouldWork() throws RedisDriverException {
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);

        RxRedlock rxLock = new AsyncRedlockImpl("test-rx", drivers, testConfig, Executors.newCachedThreadPool(),
                scheduler);

        TestObserver<Boolean> observer = rxLock.tryLockRx(Duration.ofSeconds(1)).test();
        observer.awaitDone(5, TimeUnit.SECONDS);

        observer.assertValue(true);
        observer.assertComplete();
    }
}
