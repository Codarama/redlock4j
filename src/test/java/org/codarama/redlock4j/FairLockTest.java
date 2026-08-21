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
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

        FairLock lock = new FairLock("test-fair", drivers, testConfig, null);
        boolean acquired = lock.tryLock(Duration.ofSeconds(1));

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

        FairLock lock = new FairLock("test-fair", drivers, testConfig, null);

        // A zero timeout skips the deadline check, so the queue is always consulted exactly once. Under a wall-clock
        // budget a slow fan-out can expire the deadline before the first zRange, leaving those stubs unused and
        // failing the test under strict stubbing.
        boolean acquired = lock.tryLock(Duration.ZERO);

        assertFalse(acquired);
        assertFalse(lock.isHeldByCurrentThread());
    }

    // ========== Reentrancy ==========

    @Test
    void shouldSupportReentrantAcquisition() throws RedisDriverException, InterruptedException {
        setupSuccessfulAcquisition();

        FairLock lock = new FairLock("test-reentrant", drivers, testConfig, null);

        assertTrue(lock.tryLock(Duration.ofSeconds(1)));
        assertEquals(1, lock.getHoldCount());

        // Reentrant acquisition should succeed without Redis calls
        assertTrue(lock.tryLock(Duration.ofSeconds(1)));
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
        FairLock lock = new FairLock("test-validity", drivers, testConfig, null);
        assertTrue(lock.getRemainingValidityTime().isZero());
    }

    @Test
    void shouldThrowOnNewCondition() {
        FairLock lock = new FairLock("test-condition", drivers, testConfig, null);
        assertThrows(UnsupportedOperationException.class, lock::newCondition);
    }

    // ========== Timeout / Unavailable Paths ==========

    @Test
    void shouldReturnFalseWhenTimeoutExceededAndNeverReachesFront() throws RedisDriverException, InterruptedException {
        RedlockConfiguration shortConfig = shortRetryConfig();

        // Add to queue succeeds
        when(mockDriver1.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        lenient().when(mockDriver2.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        lenient().when(mockDriver3.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);

        // Someone else is always at the front of the queue
        lenient().when(mockDriver1.zRange(anyString(), eq(0L), eq(0L)))
                .thenReturn(Collections.singletonList("other-token"));
        lenient().when(mockDriver2.zRange(anyString(), eq(0L), eq(0L)))
                .thenReturn(Collections.singletonList("other-token"));
        lenient().when(mockDriver3.zRange(anyString(), eq(0L), eq(0L)))
                .thenReturn(Collections.singletonList("other-token"));

        FairLock lock = new FairLock("test-timeout", drivers, shortConfig, null);

        boolean acquired = lock.tryLock(Duration.ofMillis(150));

        assertFalse(acquired);
        assertFalse(lock.isHeldByCurrentThread());
        // On the timeout branch the waiter must be removed from the queue for cleanup
        verify(mockDriver1, atLeastOnce()).zRem(anyString(), anyString());
    }

    @Test
    void shouldReturnFalseWhenAtFrontButLockAcquisitionFails() throws RedisDriverException, InterruptedException {
        RedlockConfiguration shortConfig = shortRetryConfig();

        // Add to queue and become the front-most waiter
        // FairLock generates the queue token internally, so capture what the drivers actually receive. Every stubbing
        // call stays on this thread: the zAdd answers run on parallel fan-out threads, and Mockito cannot safely stub
        // a mock while another thread is invoking it.
        AtomicReference<String> queueToken = new AtomicReference<>();

        when(mockDriver1.zAdd(anyString(), anyDouble(), anyString())).thenAnswer(inv -> {
            queueToken.set(inv.getArgument(2));
            return true;
        });
        lenient().when(mockDriver2.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        lenient().when(mockDriver3.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        // The head of the queue is our own token, so this thread is the front-most waiter.
        Answer<List<String>> queueHeadIsOurToken = inv -> Collections.singletonList(queueToken.get());
        lenient().when(mockDriver1.zRange(anyString(), eq(0L), eq(0L))).thenAnswer(queueHeadIsOurToken);
        lenient().when(mockDriver2.zRange(anyString(), eq(0L), eq(0L))).thenAnswer(queueHeadIsOurToken);
        lenient().when(mockDriver3.zRange(anyString(), eq(0L), eq(0L))).thenAnswer(queueHeadIsOurToken);

        // We are at the front, but the underlying lock cannot be acquired (quorum not met)
        lenient().when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        lenient().when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        lenient().when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);

        FairLock lock = new FairLock("test-acquire-fail", drivers, shortConfig, null);

        boolean acquired = lock.tryLock(Duration.ofMillis(150));

        assertFalse(acquired);
        assertFalse(lock.isHeldByCurrentThread());
        verify(mockDriver1, atLeastOnce()).zRem(anyString(), anyString());
    }

    @Test
    void shouldReturnFalseForZeroTimeoutWhenUnavailable() throws RedisDriverException {
        // Add to queue succeeds
        when(mockDriver1.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        lenient().when(mockDriver2.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        lenient().when(mockDriver3.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);

        // Someone else is at the front, so immediate acquisition is impossible
        when(mockDriver1.zRange(anyString(), eq(0L), eq(0L))).thenReturn(Collections.singletonList("other-token"));
        lenient().when(mockDriver2.zRange(anyString(), eq(0L), eq(0L)))
                .thenReturn(Collections.singletonList("other-token"));
        lenient().when(mockDriver3.zRange(anyString(), eq(0L), eq(0L)))
                .thenReturn(Collections.singletonList("other-token"));

        FairLock lock = new FairLock("test-zero-timeout", drivers, testConfig, null);

        boolean acquired = lock.tryLock();

        assertFalse(acquired);
        assertFalse(lock.isHeldByCurrentThread());
        verify(mockDriver1, atLeastOnce()).zRem(anyString(), anyString());
    }

    // ========== lock() / lockInterruptibly() Failure Paths ==========

    @Test
    void shouldThrowRedlockExceptionWhenLockCannotBeAcquired() throws RedisDriverException {
        RedlockConfiguration shortConfig = shortAcquisitionConfig();

        when(mockDriver1.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        lenient().when(mockDriver2.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        lenient().when(mockDriver3.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);

        lenient().when(mockDriver1.zRange(anyString(), eq(0L), eq(0L)))
                .thenReturn(Collections.singletonList("other-token"));
        lenient().when(mockDriver2.zRange(anyString(), eq(0L), eq(0L)))
                .thenReturn(Collections.singletonList("other-token"));
        lenient().when(mockDriver3.zRange(anyString(), eq(0L), eq(0L)))
                .thenReturn(Collections.singletonList("other-token"));

        FairLock lock = new FairLock("test-lock-fail", drivers, shortConfig, null);

        assertThrows(RedlockException.class, lock::lock);
        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    void shouldThrowRedlockExceptionFromLockInterruptiblyWhenLockCannotBeAcquired() throws RedisDriverException {
        RedlockConfiguration shortConfig = shortAcquisitionConfig();

        when(mockDriver1.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        lenient().when(mockDriver2.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        lenient().when(mockDriver3.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);

        lenient().when(mockDriver1.zRange(anyString(), eq(0L), eq(0L)))
                .thenReturn(Collections.singletonList("other-token"));
        lenient().when(mockDriver2.zRange(anyString(), eq(0L), eq(0L)))
                .thenReturn(Collections.singletonList("other-token"));
        lenient().when(mockDriver3.zRange(anyString(), eq(0L), eq(0L)))
                .thenReturn(Collections.singletonList("other-token"));

        FairLock lock = new FairLock("test-lock-interruptibly-fail", drivers, shortConfig, null);

        assertThrows(RedlockException.class, lock::lockInterruptibly);
        assertFalse(lock.isHeldByCurrentThread());
    }

    // ========== Interruption Paths ==========

    @Test
    void shouldThrowInterruptedExceptionAndCleanupQueueWhenInterrupted() throws RedisDriverException {
        // Add to queue succeeds so the acquisition loop is entered
        when(mockDriver1.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        lenient().when(mockDriver2.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        lenient().when(mockDriver3.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);

        FairLock lock = new FairLock("test-interrupt", drivers, testConfig, null);

        // Pre-interrupt the current thread so the loop's interrupt check trips immediately
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> lock.tryLock(Duration.ofSeconds(1)));
            assertFalse(lock.isHeldByCurrentThread());
            // The catch/interrupt branch must remove the waiter from the queue
            verify(mockDriver1, atLeastOnce()).zRem(anyString(), anyString());
        } finally {
            // Clear any lingering interrupt status for subsequent tests
            Thread.interrupted();
        }
    }

    @Test
    void shouldThrowRedlockExceptionAndPreserveInterruptWhenLockCalledInterrupted() throws RedisDriverException {
        when(mockDriver1.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        lenient().when(mockDriver2.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        lenient().when(mockDriver3.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);

        FairLock lock = new FairLock("test-lock-interrupt", drivers, testConfig, null);

        Thread.currentThread().interrupt();
        try {
            // lock() catches InterruptedException, re-sets the flag, and wraps it in a RedlockException
            assertThrows(RedlockException.class, lock::lock);
            assertTrue(Thread.currentThread().isInterrupted(), "lock() must re-set the interrupt flag");
        } finally {
            Thread.interrupted();
        }
    }

    private RedlockConfiguration shortRetryConfig() {
        return RedlockConfiguration.builder().addRedisNode("localhost", 6379).addRedisNode("localhost", 6380)
                .addRedisNode("localhost", 6381).defaultLockTimeout(Duration.ofSeconds(30))
                .retryDelay(Duration.ofMillis(10)).maxRetryAttempts(3).lockAcquisitionTimeout(Duration.ofSeconds(10))
                .build();
    }

    private RedlockConfiguration shortAcquisitionConfig() {
        return RedlockConfiguration.builder().addRedisNode("localhost", 6379).addRedisNode("localhost", 6380)
                .addRedisNode("localhost", 6381).defaultLockTimeout(Duration.ofSeconds(30))
                .retryDelay(Duration.ofMillis(10)).maxRetryAttempts(3).lockAcquisitionTimeout(Duration.ofMillis(150))
                .build();
    }

    private void setupSuccessfulAcquisition() throws RedisDriverException {
        // FairLock generates the queue token internally, so capture what the drivers actually receive. Every stubbing
        // call stays on this thread: the zAdd answers run on parallel fan-out threads, and Mockito cannot safely stub
        // a mock while another thread is invoking it.
        AtomicReference<String> queueToken = new AtomicReference<>();

        when(mockDriver1.zAdd(anyString(), anyDouble(), anyString())).thenAnswer(inv -> {
            queueToken.set(inv.getArgument(2));
            return true;
        });
        when(mockDriver2.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);
        when(mockDriver3.zAdd(anyString(), anyDouble(), anyString())).thenReturn(true);

        // The head of the queue is our own token, so this thread is the front-most waiter.
        Answer<List<String>> queueHeadIsOurToken = inv -> Collections.singletonList(queueToken.get());
        lenient().when(mockDriver1.zRange(anyString(), eq(0L), eq(0L))).thenAnswer(queueHeadIsOurToken);
        lenient().when(mockDriver2.zRange(anyString(), eq(0L), eq(0L))).thenAnswer(queueHeadIsOurToken);
        lenient().when(mockDriver3.zRange(anyString(), eq(0L), eq(0L))).thenAnswer(queueHeadIsOurToken);

        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
    }
}
