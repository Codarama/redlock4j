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

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * Comprehensive example demonstrating all available lock APIs:
 * - Standard Java Lock interface
 * - CompletionStage asynchronous API
 * - RxJava reactive API
 */
public class AsyncRxUsageExample {
    
    public static void main(String[] args) {
        // Configure Redis nodes
        RedlockConfiguration config = RedlockConfiguration.builder()
            .addRedisNode("localhost", 6379)
            .addRedisNode("localhost", 6380)
            .addRedisNode("localhost", 6381)
            .defaultLockTimeout(30, TimeUnit.SECONDS)
            .retryDelay(200, TimeUnit.MILLISECONDS)
            .maxRetryAttempts(3)
            .lockAcquisitionTimeout(10, TimeUnit.SECONDS)
            .build();
        
        try (RedlockManager redlockManager = RedlockManager.withJedis(config)) {
            
            // Example 1: Standard Java Lock Interface
            demonstrateStandardLock(redlockManager);
            
            // Example 2: CompletionStage Asynchronous API
            demonstrateCompletionStageAsync(redlockManager);
            
            // Example 3: RxJava Reactive API
            demonstrateRxJavaReactive(redlockManager);
            
            // Example 4: Combined Async/Reactive Lock
            demonstrateCombinedLock(redlockManager);
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
    
    private static void demonstrateStandardLock(RedlockManager manager) {
        System.out.println("\n=== Standard Java Lock Interface ===");
        
        Lock lock = manager.createLock("standard-lock-resource");
        
        // Traditional lock usage
        lock.lock();
        try {
            System.out.println("✅ Standard lock acquired");
            System.out.println("Performing critical work...");
            Thread.sleep(1000);
            System.out.println("Work completed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
            System.out.println("✅ Standard lock released");
        }
        
        // Try lock with timeout
        try {
            if (lock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    System.out.println("✅ Standard lock acquired with timeout");
                } finally {
                    lock.unlock();
                    System.out.println("✅ Standard lock released");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private static void demonstrateCompletionStageAsync(RedlockManager manager) {
        System.out.println("\n=== CompletionStage Asynchronous API ===");
        
        AsyncRedlock asyncLock = manager.createAsyncLock("async-resource");
        
        // Async lock with CompletionStage
        CompletionStage<Boolean> lockFuture = asyncLock.tryLockAsync();
        
        lockFuture
            .thenAccept(acquired -> {
                if (acquired) {
                    System.out.println("✅ Async lock acquired successfully!");
                    System.out.println("Lock key: " + asyncLock.getLockKey());
                    System.out.println("Held by current thread: " + asyncLock.isHeldByCurrentThread());
                    System.out.println("Remaining validity: " + asyncLock.getRemainingValidityTime() + "ms");
                    
                    // Simulate async work
                    try {
                        Thread.sleep(2000);
                        System.out.println("Async work completed");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    System.out.println("❌ Failed to acquire async lock");
                }
            })
            .thenCompose(v -> {
                // Async unlock
                System.out.println("Releasing async lock...");
                return asyncLock.unlockAsync();
            })
            .thenRun(() -> System.out.println("✅ Async lock released successfully!"))
            .exceptionally(throwable -> {
                System.err.println("❌ Async lock error: " + throwable.getMessage());
                return null;
            })
            .toCompletableFuture()
            .join(); // Wait for completion in this example
        
        // Async lock with timeout
        asyncLock.tryLockAsync(3, TimeUnit.SECONDS)
            .thenAccept(acquired -> {
                System.out.println("Async lock with timeout: " + (acquired ? "✅ Success" : "❌ Failed"));
                if (acquired) {
                    asyncLock.unlockAsync().toCompletableFuture().join();
                }
            })
            .toCompletableFuture()
            .join();
    }
    
    private static void demonstrateRxJavaReactive(RedlockManager manager) {
        System.out.println("\n=== RxJava Reactive API ===");
        
        RxRedlock rxLock = manager.createRxLock("rxjava-resource");
        
        // RxJava Single for lock acquisition
        Single<Boolean> lockSingle = rxLock.tryLockRx();
        
        Disposable lockDisposable = lockSingle
            .subscribe(
                acquired -> {
                    if (acquired) {
                        System.out.println("✅ RxJava lock acquired successfully!");
                        System.out.println("Lock key: " + rxLock.getLockKey());
                        System.out.println("Held by current thread: " + rxLock.isHeldByCurrentThread());
                        
                        // Start RxJava validity monitoring
                        startRxValidityMonitoring(rxLock);
                        
                        // Start lock state monitoring
                        startRxLockStateMonitoring(rxLock);
                        
                        // Simulate work
                        try {
                            Thread.sleep(4000);
                            System.out.println("RxJava work completed");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        
                        // Release lock with RxJava Completable
                        Completable unlockCompletable = rxLock.unlockRx();
                        Disposable result = unlockCompletable.subscribe(
                            () -> System.out.println("✅ RxJava lock released successfully!"),
                            throwable -> System.err.println("❌ RxJava unlock error: " + throwable.getMessage())
                        );
                    } else {
                        System.out.println("❌ Failed to acquire RxJava lock");
                    }
                },
                throwable -> System.err.println("❌ RxJava lock error: " + throwable.getMessage())
            );
        
        // Wait for RxJava operations to complete
        try {
            Thread.sleep(6000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        if (!lockDisposable.isDisposed()) {
            lockDisposable.dispose();
        }
    }
    
    private static void startRxValidityMonitoring(RxRedlock rxLock) {
        System.out.println("📊 Starting RxJava validity monitoring...");
        
        Observable<Long> validityObservable = rxLock.validityObservable(1, TimeUnit.SECONDS);
        
        Disposable validityDisposable = validityObservable
            .take(3) // Take only 3 emissions
            .subscribe(
                validityTime -> System.out.println("📊 RxJava validity: " + validityTime + "ms remaining"),
                throwable -> System.err.println("❌ RxJava validity error: " + throwable.getMessage()),
                () -> System.out.println("🏁 RxJava validity monitoring completed")
            );
    }
    
    private static void startRxLockStateMonitoring(RxRedlock rxLock) {
        System.out.println("📊 Starting RxJava lock state monitoring...");
        
        Observable<RxRedlock.LockState> stateObservable = rxLock.lockStateObservable();
        
        Disposable stateDisposable = stateObservable
            .take(3) // Monitor a few state changes
            .subscribe(
                state -> System.out.println("📊 RxJava lock state: " + state),
                throwable -> System.err.println("❌ RxJava state monitoring error: " + throwable.getMessage()),
                () -> System.out.println("🏁 RxJava state monitoring completed")
            );
    }
    
    private static void demonstrateCombinedLock(RedlockManager manager) {
        System.out.println("\n=== Combined Async/Reactive Lock ===");
        
        AsyncRxRedlock combinedLock = manager.createAsyncRxLock("combined-resource");
        
        // Use CompletionStage interface for acquisition
        System.out.println("🔄 Acquiring lock via CompletionStage interface...");
        combinedLock.tryLockAsync()
            .thenAccept(acquired -> {
                if (acquired) {
                    System.out.println("✅ Lock acquired via CompletionStage interface!");
                    
                    // Use RxJava interface for monitoring
                    System.out.println("📊 Monitoring via RxJava interface...");
                    Observable<Long> validityObservable = combinedLock.validityObservable(500, TimeUnit.MILLISECONDS);
                    
                    Disposable monitoringDisposable = validityObservable
                        .take(2)
                        .subscribe(
                            validityTime -> System.out.println("📊 Combined lock validity: " + validityTime + "ms"),
                            throwable -> System.err.println("❌ Monitoring error: " + throwable.getMessage()),
                            () -> {
                                System.out.println("🏁 Combined lock monitoring completed");
                                
                                // Release via CompletionStage interface
                                System.out.println("🔄 Releasing lock via CompletionStage interface...");
                                combinedLock.unlockAsync()
                                    .thenRun(() -> System.out.println("✅ Combined lock released!"))
                                    .exceptionally(throwable -> {
                                        System.err.println("❌ Release error: " + throwable.getMessage());
                                        return null;
                                    });
                            }
                        );
                } else {
                    System.out.println("❌ Failed to acquire combined lock");
                }
            })
            .exceptionally(throwable -> {
                System.err.println("❌ Combined lock error: " + throwable.getMessage());
                return null;
            })
            .toCompletableFuture()
            .join();
        
        // Wait for operations to complete
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        System.out.println("\n🎉 All examples completed!");
    }
}
