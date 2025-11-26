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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for lock extension functionality.
 */
@ExtendWith(MockitoExtension.class)
public class LockExtensionTest {
    
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
    public void testExtendSuccess() throws RedisDriverException {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        
        // Mock successful extension on all nodes
        when(mockDriver1.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        
        // Acquire lock
        assertTrue(lock.tryLock());
        long initialValidity = lock.getRemainingValidityTime();
        
        // Extend lock
        boolean extended = lock.extend(10000); // Extend by 10 seconds
        
        assertTrue(extended);
        assertTrue(lock.isHeldByCurrentThread());
        
        // Validity time should be greater after extension
        long newValidity = lock.getRemainingValidityTime();
        assertTrue(newValidity > initialValidity, 
            "New validity (" + newValidity + ") should be greater than initial (" + initialValidity + ")");
        
        // Verify setIfValueMatches was called on all drivers
        verify(mockDriver1).setIfValueMatches(eq("test-key"), anyString(), anyString(), eq(40000L));
        verify(mockDriver2).setIfValueMatches(eq("test-key"), anyString(), anyString(), eq(40000L));
        verify(mockDriver3).setIfValueMatches(eq("test-key"), anyString(), anyString(), eq(40000L));
    }
    
    @Test
    public void testExtendWithQuorum() throws RedisDriverException {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        
        // Mock extension succeeds on quorum (2 out of 3)
        when(mockDriver1.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(false);
        
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        
        // Acquire lock
        assertTrue(lock.tryLock());
        
        // Extend lock - should succeed with quorum
        boolean extended = lock.extend(10000);
        
        assertTrue(extended);
        assertTrue(lock.isHeldByCurrentThread());
    }
    
    @Test
    public void testExtendFailsWithoutQuorum() throws RedisDriverException {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        
        // Mock extension fails on majority (only 1 out of 3 succeeds)
        when(mockDriver1.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(false);
        when(mockDriver3.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(false);
        
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        
        // Acquire lock
        assertTrue(lock.tryLock());
        
        // Extend lock - should fail without quorum
        boolean extended = lock.extend(10000);
        
        assertFalse(extended);
        // Lock should still be held (original lock not affected)
        assertTrue(lock.isHeldByCurrentThread());
    }
    
    @Test
    public void testExtendWithoutHoldingLock() {
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        
        // Try to extend without holding lock
        boolean extended = lock.extend(10000);
        
        assertFalse(extended);
        assertFalse(lock.isHeldByCurrentThread());
    }
    
    @Test
    public void testExtendWithNegativeTime() throws RedisDriverException {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        
        // Acquire lock
        assertTrue(lock.tryLock());
        
        // Try to extend with negative time
        assertThrows(IllegalArgumentException.class, () -> lock.extend(-1000));
    }
    
    @Test
    public void testExtendWithZeroTime() throws RedisDriverException {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        
        // Acquire lock
        assertTrue(lock.tryLock());
        
        // Try to extend with zero time
        assertThrows(IllegalArgumentException.class, () -> lock.extend(0));
    }
    
    @Test
    public void testExtendPreservesHoldCount() throws RedisDriverException {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        
        // Mock successful extension
        when(mockDriver1.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        
        // Acquire lock twice (reentrant)
        assertTrue(lock.tryLock());
        assertTrue(lock.tryLock());
        assertEquals(2, lock.getHoldCount());
        
        // Extend lock
        assertTrue(lock.extend(10000));
        
        // Hold count should be preserved
        assertEquals(2, lock.getHoldCount());
        
        // Should need to unlock twice
        lock.unlock();
        assertEquals(1, lock.getHoldCount());
        assertTrue(lock.isHeldByCurrentThread());
        
        lock.unlock();
        assertEquals(0, lock.getHoldCount());
        assertFalse(lock.isHeldByCurrentThread());
    }
    
    @Test
    public void testExtendWithDriverException() throws RedisDriverException {
        // Mock successful lock acquisition
        when(mockDriver1.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver3.setIfNotExists(anyString(), anyString(), anyLong())).thenReturn(true);
        
        // Mock extension with one driver throwing exception
        when(mockDriver1.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        when(mockDriver2.setIfValueMatches(anyString(), anyString(), anyString(), anyLong()))
            .thenThrow(new RedisDriverException("Connection failed"));
        when(mockDriver3.setIfValueMatches(anyString(), anyString(), anyString(), anyLong())).thenReturn(true);
        
        Redlock lock = new Redlock("test-key", drivers, testConfig);
        
        // Acquire lock
        assertTrue(lock.tryLock());
        
        // Extend should still succeed with quorum (2 out of 3)
        boolean extended = lock.extend(10000);
        
        assertTrue(extended);
        assertTrue(lock.isHeldByCurrentThread());
    }
}

