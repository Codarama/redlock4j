/*
 * MIT License
 *
 * Copyright (c) 2025 Codarama
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.codarama.redlock4j;

import org.codarama.redlock4j.configuration.RedlockConfiguration;
import org.codarama.redlock4j.driver.RedisDriver;
import org.codarama.redlock4j.driver.RedisDriverException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for reentrant functionality of Redlock using Mockito mocks.
 */
@ExtendWith(MockitoExtension.class)
public class ReentrantRedlockTest {
    
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
        testConfig = RedlockConfiguration.builder()
            .addRedisNode("localhost", 6379)
            .addRedisNode("localhost", 6380)
            .addRedisNode("localhost", 6381)
            .defaultLockTimeout(Duration.ofSeconds(30))
            .retryDelay(Duration.ofMillis(100))
            .maxRetryAttempts(3)
            .lockAcquisitionTimeout(Duration.ofSeconds(10))
            .build();
        
        drivers = Arrays.asList(mockDriver1, mockDriver2, mockDriver3);
        
        // Setup default mock behavior
        lenient().when(mockDriver1.getIdentifier()).thenReturn("redis://localhost:6379");
        lenient().when(mockDriver2.getIdentifier()).thenReturn("redis://localhost:6380");
        lenient().when(mockDriver3.getIdentifier()).thenReturn("redis://localhost:6381");
    }
    
    @Test
    public void testBasicReentrancy() throws RedisDriverException {
        // Mock successful lock acquisition on quorum
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        
        // First acquisition
        assertTrue(lock.tryLock());
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(1, lock.getHoldCount());
        
        // Second acquisition (reentrant)
        assertTrue(lock.tryLock());
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(2, lock.getHoldCount());
        
        // Third acquisition (reentrant)
        assertTrue(lock.tryLock());
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(3, lock.getHoldCount());
        
        // First unlock
        lock.unlock();
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(2, lock.getHoldCount());
        
        // Second unlock
        lock.unlock();
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(1, lock.getHoldCount());
        
        // Final unlock
        lock.unlock();
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
    public void testReentrantLockWithTimeout() throws RedisDriverException, InterruptedException {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        
        // First acquisition
        assertTrue(lock.tryLock(1, TimeUnit.SECONDS));
        assertEquals(1, lock.getHoldCount());
        
        // Reentrant acquisition with timeout
        assertTrue(lock.tryLock(1, TimeUnit.SECONDS));
        assertEquals(2, lock.getHoldCount());
        
        // Unlock both
        lock.unlock();
        assertEquals(1, lock.getHoldCount());
        lock.unlock();
        assertEquals(0, lock.getHoldCount());
        
        // Verify Redis operations were called only once
        verify(mockDriver1, times(1)).setIfNotExists(anyString(), anyString(), anyLong());
    }
    
    @Test
    public void testReentrantLockAcrossThreads() throws RedisDriverException, InterruptedException {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        
        // Acquire lock in main thread
        assertTrue(lock.tryLock());
        assertEquals(1, lock.getHoldCount());
        
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger otherThreadHoldCount = new AtomicInteger(-1);
        
        // Try to access from another thread
        Thread otherThread = new Thread(() -> {
            // Other thread should not see the lock as held
            assertFalse(lock.isHeldByCurrentThread());
            assertEquals(0, lock.getHoldCount());
            otherThreadHoldCount.set(lock.getHoldCount());
            latch.countDown();
        });
        
        otherThread.start();
        latch.await(5, TimeUnit.SECONDS);
        
        // Main thread should still hold the lock
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(1, lock.getHoldCount());
        assertEquals(0, otherThreadHoldCount.get());
        
        lock.unlock();
        assertFalse(lock.isHeldByCurrentThread());
    }
    
    @Test
    public void testUnlockWithoutLockDoesNotThrow() {
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        
        // Should not throw exception when unlocking without holding lock
        assertDoesNotThrow(() -> lock.unlock());
        assertEquals(0, lock.getHoldCount());
    }
    
    @Test
    public void testReentrantLockWithLockMethod() throws RedisDriverException {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        
        // First acquisition using lock()
        assertDoesNotThrow(() -> lock.lock());
        assertEquals(1, lock.getHoldCount());
        
        // Reentrant acquisition using tryLock()
        assertTrue(lock.tryLock());
        assertEquals(2, lock.getHoldCount());
        
        // Unlock both
        lock.unlock();
        assertEquals(1, lock.getHoldCount());
        lock.unlock();
        assertEquals(0, lock.getHoldCount());
    }
    
    @Test
    public void testReentrantLockWithLockInterruptibly() throws RedisDriverException, InterruptedException {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        
        // First acquisition using lockInterruptibly()
        assertDoesNotThrow(() -> lock.lockInterruptibly());
        assertEquals(1, lock.getHoldCount());
        
        // Reentrant acquisition using lockInterruptibly()
        assertDoesNotThrow(() -> lock.lockInterruptibly());
        assertEquals(2, lock.getHoldCount());
        
        // Unlock both
        lock.unlock();
        assertEquals(1, lock.getHoldCount());
        lock.unlock();
        assertEquals(0, lock.getHoldCount());
    }
    
    @Test
    public void testConcurrentReentrantAccess() throws InterruptedException, RedisDriverException {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(3);
        
        // Acquire lock in main thread
        assertTrue(lock.tryLock());
        assertEquals(1, lock.getHoldCount());
        
        // Submit tasks that try to acquire the same lock from different threads
        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Each thread should see the lock as not held by them
                    assertFalse(lock.isHeldByCurrentThread());
                    assertEquals(0, lock.getHoldCount());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            });
        }
        
        startLatch.countDown();
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        
        // Main thread should still hold the lock
        assertTrue(lock.isHeldByCurrentThread());
        assertEquals(1, lock.getHoldCount());
        
        lock.unlock();
        executor.shutdown();
    }
}
