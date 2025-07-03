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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * Example demonstrating how to use the Redlock implementation.
 */
public class RedlockUsageExample {
    
    public static void main(String[] args) {
        // Configure Redis nodes (minimum 3 for proper Redlock operation)
        RedlockConfiguration config = RedlockConfiguration.builder()
            .addRedisNode("localhost", 6379)
            .addRedisNode("localhost", 6380)
            .addRedisNode("localhost", 6381)
            .defaultLockTimeout(30, TimeUnit.SECONDS)
            .retryDelay(200, TimeUnit.MILLISECONDS)
            .maxRetryAttempts(3)
            .lockAcquisitionTimeout(10, TimeUnit.SECONDS)
            .build();
        
        // Create RedlockManager with Jedis (or use withLettuce for Lettuce)
        try (RedlockManager redlockManager = RedlockManager.withJedis(config)) {
            
            // Check if the manager is healthy
            if (!redlockManager.isHealthy()) {
                System.err.println("RedlockManager is not healthy - not enough connected nodes");
                return;
            }
            
            // Create a lock for a specific resource
            Lock lock = redlockManager.createLock("my-resource-key");
            
            // Example 1: Simple lock/unlock
            System.out.println("Attempting to acquire lock...");
            lock.lock();
            try {
                System.out.println("Lock acquired! Performing critical section work...");
                // Do your critical section work here
                Thread.sleep(5000); // Simulate work
                System.out.println("Work completed.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Work interrupted");
            } finally {
                lock.unlock();
                System.out.println("Lock released.");
            }
            
            // Example 2: Try lock with timeout
            System.out.println("\nAttempting to acquire lock with timeout...");
            try {
                if (lock.tryLock(5, TimeUnit.SECONDS)) {
                    try {
                        System.out.println("Lock acquired with timeout! Performing work...");
                        Thread.sleep(2000);
                        System.out.println("Work completed.");
                    } finally {
                        lock.unlock();
                        System.out.println("Lock released.");
                    }
                } else {
                    System.out.println("Failed to acquire lock within timeout.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Lock acquisition interrupted");
            }
            
            // Example 3: Non-blocking try lock
            System.out.println("\nAttempting non-blocking lock acquisition...");
            if (lock.tryLock()) {
                try {
                    System.out.println("Lock acquired immediately! Performing work...");
                    Thread.sleep(1000);
                    System.out.println("Work completed.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Work interrupted");
                } finally {
                    lock.unlock();
                    System.out.println("Lock released.");
                }
            } else {
                System.out.println("Lock is currently held by another process.");
            }
            
            // Example 4: Using Redlock specific methods
            if (lock instanceof Redlock) {
                Redlock redlock = (Redlock) lock;
                
                if (redlock.tryLock()) {
                    try {
                        System.out.println("\nLock acquired. Checking lock state...");
                        System.out.println("Is held by current thread: " + redlock.isHeldByCurrentThread());
                        System.out.println("Remaining validity time: " + redlock.getRemainingValidityTime() + "ms");
                        
                        Thread.sleep(1000);
                        
                        System.out.println("After 1 second...");
                        System.out.println("Remaining validity time: " + redlock.getRemainingValidityTime() + "ms");
                        
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        redlock.unlock();
                        System.out.println("Lock released.");
                    }
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
        
        System.out.println("\nExample completed.");
    }
}
