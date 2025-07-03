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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

/**
 * Performance tests for Redlock functionality using Testcontainers.
 * These tests are disabled by default as they are for performance analysis.
 */
@Disabled("Performance tests - enable manually when needed")
@Testcontainers
public class RedlockPerformanceTest {

    // Create 3 Redis containers for performance testing
    @Container
    static GenericContainer<?> redis1 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");

    @Container
    static GenericContainer<?> redis2 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");

    @Container
    static GenericContainer<?> redis3 = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");

    private static RedlockConfiguration testConfiguration;

    @BeforeAll
    static void setUp() {
        // Create configuration with dynamic ports from containers
        testConfiguration = RedlockConfiguration.builder()
            .addRedisNode("localhost", redis1.getMappedPort(6379))
            .addRedisNode("localhost", redis2.getMappedPort(6379))
            .addRedisNode("localhost", redis3.getMappedPort(6379))
            .defaultLockTimeout(5, TimeUnit.SECONDS)
            .retryDelay(50, TimeUnit.MILLISECONDS)
            .maxRetryAttempts(2)
            .lockAcquisitionTimeout(2, TimeUnit.SECONDS)
            .build();
    }
    
    @Test
    public void testLockAcquisitionPerformance() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            int iterations = 1000;
            long startTime = System.currentTimeMillis();
            
            for (int i = 0; i < iterations; i++) {
                Lock lock = manager.createLock("perf-test-" + i);
                if (lock.tryLock()) {
                    try {
                        // Simulate some work
                        Thread.sleep(1);
                    } finally {
                        lock.unlock();
                    }
                }
            }
            
            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;
            double avgTime = (double) totalTime / iterations;
            
            System.out.println("Lock acquisition performance test:");
            System.out.println("Total iterations: " + iterations);
            System.out.println("Total time: " + totalTime + "ms");
            System.out.println("Average time per lock: " + String.format("%.2f", avgTime) + "ms");
            System.out.println("Locks per second: " + String.format("%.2f", 1000.0 / avgTime));
        }
    }
    
    @Test
    public void testConcurrentLockContention() throws InterruptedException {
        try (RedlockManager manager = RedlockManager.withJedis(testConfiguration)) {
            int threadCount = 10;
            int iterationsPerThread = 100;
            String lockKey = "contention-test-lock";
            
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger successfulLocks = new AtomicInteger(0);
            AtomicInteger failedLocks = new AtomicInteger(0);
            
            long startTime = System.currentTimeMillis();
            
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await(); // Wait for all threads to be ready
                        
                        for (int j = 0; j < iterationsPerThread; j++) {
                            Lock lock = manager.createLock(lockKey);
                            if (lock.tryLock(100, TimeUnit.MILLISECONDS)) {
                                try {
                                    successfulLocks.incrementAndGet();
                                    // Simulate some work
                                    Thread.sleep(1);
                                } finally {
                                    lock.unlock();
                                }
                            } else {
                                failedLocks.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }
            
            startLatch.countDown(); // Start all threads
            endLatch.await(); // Wait for all threads to complete
            
            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;
            
            executor.shutdown();
            
            System.out.println("\nConcurrent lock contention test:");
            System.out.println("Threads: " + threadCount);
            System.out.println("Iterations per thread: " + iterationsPerThread);
            System.out.println("Total attempts: " + (threadCount * iterationsPerThread));
            System.out.println("Successful locks: " + successfulLocks.get());
            System.out.println("Failed locks: " + failedLocks.get());
            System.out.println("Success rate: " + String.format("%.2f", 
                (double) successfulLocks.get() / (threadCount * iterationsPerThread) * 100) + "%");
            System.out.println("Total time: " + totalTime + "ms");
            System.out.println("Average time per attempt: " + 
                String.format("%.2f", (double) totalTime / (threadCount * iterationsPerThread)) + "ms");
        }
    }
    
    @Test
    public void testJedisVsLettucePerformance() throws InterruptedException {
        int iterations = 500;

        // Test Jedis performance
        long jedisTime = testDriverPerformance("Jedis",
            RedlockManager.withJedis(testConfiguration), iterations);

        // Test Lettuce performance
        long lettuceTime = testDriverPerformance("Lettuce",
            RedlockManager.withLettuce(testConfiguration), iterations);
        
        System.out.println("\nDriver Performance Comparison:");
        System.out.println("Jedis total time: " + jedisTime + "ms");
        System.out.println("Lettuce total time: " + lettuceTime + "ms");
        System.out.println("Jedis avg per lock: " + String.format("%.2f", (double) jedisTime / iterations) + "ms");
        System.out.println("Lettuce avg per lock: " + String.format("%.2f", (double) lettuceTime / iterations) + "ms");
        
        if (jedisTime < lettuceTime) {
            System.out.println("Jedis is " + String.format("%.2f", (double) lettuceTime / jedisTime) + "x faster");
        } else {
            System.out.println("Lettuce is " + String.format("%.2f", (double) jedisTime / lettuceTime) + "x faster");
        }
    }
    
    private long testDriverPerformance(String driverName, RedlockManager manager, int iterations)
            throws InterruptedException {
        try {
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < iterations; i++) {
                Lock lock = manager.createLock("perf-test-" + driverName.toLowerCase() + "-" + i);
                if (lock.tryLock()) {
                    try {
                        // Simulate minimal work
                        Thread.sleep(1);
                    } finally {
                        lock.unlock();
                    }
                }
            }

            long endTime = System.currentTimeMillis();
            return endTime - startTime;
        } finally {
            manager.close();
        }
    }
    
    @Test
    public void testLockValidityTimeAccuracy() throws InterruptedException {
        RedlockConfiguration config = RedlockConfiguration.builder()
            .addRedisNode("localhost", redis1.getMappedPort(6379))
            .addRedisNode("localhost", redis2.getMappedPort(6379))
            .addRedisNode("localhost", redis3.getMappedPort(6379))
            .defaultLockTimeout(2, TimeUnit.SECONDS) // Short timeout for testing
            .retryDelay(50, TimeUnit.MILLISECONDS)
            .maxRetryAttempts(1)
            .build();

        try (RedlockManager manager = RedlockManager.withJedis(config)) {
            Lock lock = manager.createLock("validity-test-lock");
            
            if (lock.tryLock() && lock instanceof Redlock) {
                Redlock redlock = (Redlock) lock;
                
                long initialValidity = redlock.getRemainingValidityTime();
                System.out.println("\nLock validity time test:");
                System.out.println("Initial validity time: " + initialValidity + "ms");
                
                Thread.sleep(500);
                
                long afterDelay = redlock.getRemainingValidityTime();
                System.out.println("Validity after 500ms: " + afterDelay + "ms");
                System.out.println("Actual time passed: " + (initialValidity - afterDelay) + "ms");
                
                redlock.unlock();
            }
        }
    }
}
