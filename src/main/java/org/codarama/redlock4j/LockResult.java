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

/**
 * Result of a lock acquisition attempt.
 */
public class LockResult {
    private final boolean acquired;
    private final long validityTimeMs;
    private final String lockValue;
    private final int successfulNodes;
    private final int totalNodes;
    
    public LockResult(boolean acquired, long validityTimeMs, String lockValue, int successfulNodes, int totalNodes) {
        this.acquired = acquired;
        this.validityTimeMs = validityTimeMs;
        this.lockValue = lockValue;
        this.successfulNodes = successfulNodes;
        this.totalNodes = totalNodes;
    }
    
    /**
     * @return true if the lock was successfully acquired
     */
    public boolean isAcquired() {
        return acquired;
    }
    
    /**
     * @return the remaining validity time of the lock in milliseconds
     */
    public long getValidityTimeMs() {
        return validityTimeMs;
    }
    
    /**
     * @return the unique value associated with this lock
     */
    public String getLockValue() {
        return lockValue;
    }
    
    /**
     * @return the number of Redis nodes that successfully acquired the lock
     */
    public int getSuccessfulNodes() {
        return successfulNodes;
    }
    
    /**
     * @return the total number of Redis nodes
     */
    public int getTotalNodes() {
        return totalNodes;
    }
    
    @Override
    public String toString() {
        return "LockResult{" +
                "acquired=" + acquired +
                ", validityTimeMs=" + validityTimeMs +
                ", successfulNodes=" + successfulNodes +
                ", totalNodes=" + totalNodes +
                '}';
    }
}
