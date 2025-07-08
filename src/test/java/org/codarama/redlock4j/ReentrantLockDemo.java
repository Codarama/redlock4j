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

import org.codarama.redlock4j.async.AsyncRedlockImpl;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Demonstration of reentrant lock functionality.
 */
@ExtendWith(MockitoExtension.class)
public class ReentrantLockDemo {
    
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
    public void demonstrateReentrantLockUsage() throws RedisDriverException {
        // Mock successful lock acquisition on quorum
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        
        Redlock lock = new Redlock("demo-key", drivers, testConfig);
        
        System.out.println("=== Reentrant Lock Demonstration ===");
        
        // Method that acquires the lock
        performCriticalSection(lock);
        
        // Verify the lock is fully released
        assertFalse(lock.isHeldByCurrentThread());
        assertEquals(0, lock.getHoldCount());
        
        System.out.println("✅ Demonstration completed successfully!");
    }
    
    private void performCriticalSection(Redlock lock) {
        System.out.println("🔒 Acquiring lock in performCriticalSection()");
        lock.lock();
        
        try {
            System.out.println("   Hold count: " + lock.getHoldCount());
            System.out.println("   Is held by current thread: " + lock.isHeldByCurrentThread());
            
            // Call another method that also needs the same lock
            performNestedOperation(lock);
            
            System.out.println("   Back in performCriticalSection()");
            System.out.println("   Hold count: " + lock.getHoldCount());
            
        } finally {
            System.out.println("🔓 Releasing lock in performCriticalSection()");
            lock.unlock();
            System.out.println("   Hold count after unlock: " + lock.getHoldCount());
        }
    }
    
    private void performNestedOperation(Redlock lock) {
        System.out.println("  🔒 Acquiring lock in performNestedOperation() (reentrant)");
        lock.lock();
        
        try {
            System.out.println("     Hold count: " + lock.getHoldCount());
            System.out.println("     Is held by current thread: " + lock.isHeldByCurrentThread());
            
            // Call yet another method that needs the lock
            performDeeplyNestedOperation(lock);
            
            System.out.println("     Back in performNestedOperation()");
            System.out.println("     Hold count: " + lock.getHoldCount());
            
        } finally {
            System.out.println("  🔓 Releasing lock in performNestedOperation()");
            lock.unlock();
            System.out.println("     Hold count after unlock: " + lock.getHoldCount());
        }
    }
    
    private void performDeeplyNestedOperation(Redlock lock) {
        System.out.println("    🔒 Acquiring lock in performDeeplyNestedOperation() (reentrant)");
        lock.lock();
        
        try {
            System.out.println("       Hold count: " + lock.getHoldCount());
            System.out.println("       Is held by current thread: " + lock.isHeldByCurrentThread());
            System.out.println("       Performing deeply nested critical work...");
            
        } finally {
            System.out.println("    🔓 Releasing lock in performDeeplyNestedOperation()");
            lock.unlock();
            System.out.println("       Hold count after unlock: " + lock.getHoldCount());
        }
    }
    
    @Test
    public void demonstrateAsyncReentrantLockUsage() throws Exception {
        // Mock successful lock acquisition on quorum
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        
        ExecutorService executorService = Executors.newFixedThreadPool(4);
        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2);
        
        try {
            AsyncRedlockImpl asyncLock = new AsyncRedlockImpl("async-demo-key", drivers, testConfig, 
                                                               executorService, scheduledExecutorService);
            
            System.out.println("\n=== Async Reentrant Lock Demonstration ===");
            
            // First acquisition
            System.out.println("🔒 First async lock acquisition");
            assertTrue(asyncLock.tryLockAsync().toCompletableFuture().get());
            System.out.println("   Hold count: " + asyncLock.getHoldCount());
            
            // Second acquisition (reentrant)
            System.out.println("🔒 Second async lock acquisition (reentrant)");
            assertTrue(asyncLock.tryLockAsync().toCompletableFuture().get());
            System.out.println("   Hold count: " + asyncLock.getHoldCount());
            
            // Third acquisition (reentrant)
            System.out.println("🔒 Third async lock acquisition (reentrant)");
            assertTrue(asyncLock.tryLockAsync().toCompletableFuture().get());
            System.out.println("   Hold count: " + asyncLock.getHoldCount());
            
            // Release locks
            System.out.println("🔓 First async unlock");
            asyncLock.unlockAsync().toCompletableFuture().get();
            System.out.println("   Hold count: " + asyncLock.getHoldCount());
            
            System.out.println("🔓 Second async unlock");
            asyncLock.unlockAsync().toCompletableFuture().get();
            System.out.println("   Hold count: " + asyncLock.getHoldCount());
            
            System.out.println("🔓 Third async unlock");
            asyncLock.unlockAsync().toCompletableFuture().get();
            System.out.println("   Hold count: " + asyncLock.getHoldCount());
            
            assertFalse(asyncLock.isHeldByCurrentThread());
            assertEquals(0, asyncLock.getHoldCount());
            
            System.out.println("✅ Async demonstration completed successfully!");
            
        } finally {
            executorService.shutdown();
            scheduledExecutorService.shutdown();
        }
    }
    
    @Test
    public void demonstrateRxReentrantLockUsage() throws Exception {
        // Mock successful lock acquisition on quorum
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(false);
        
        ExecutorService executorService = Executors.newFixedThreadPool(4);
        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2);
        
        try {
            AsyncRedlockImpl rxLock = new AsyncRedlockImpl("rx-demo-key", drivers, testConfig, 
                                                            executorService, scheduledExecutorService);
            
            System.out.println("\n=== RxJava Reentrant Lock Demonstration ===");
            
            // First acquisition using RxJava
            System.out.println("🔒 First RxJava lock acquisition");
            assertTrue(rxLock.tryLockRx().blockingGet());
            System.out.println("   Hold count: " + rxLock.getHoldCount());
            
            // Second acquisition (reentrant) using RxJava
            System.out.println("🔒 Second RxJava lock acquisition (reentrant)");
            assertTrue(rxLock.tryLockRx().blockingGet());
            System.out.println("   Hold count: " + rxLock.getHoldCount());
            
            // Release locks using RxJava
            System.out.println("🔓 First RxJava unlock");
            rxLock.unlockRx().blockingAwait();
            System.out.println("   Hold count: " + rxLock.getHoldCount());
            
            System.out.println("🔓 Second RxJava unlock");
            rxLock.unlockRx().blockingAwait();
            System.out.println("   Hold count: " + rxLock.getHoldCount());
            
            assertFalse(rxLock.isHeldByCurrentThread());
            assertEquals(0, rxLock.getHoldCount());
            
            System.out.println("✅ RxJava demonstration completed successfully!");
            
        } finally {
            executorService.shutdown();
            scheduledExecutorService.shutdown();
        }
    }
}
