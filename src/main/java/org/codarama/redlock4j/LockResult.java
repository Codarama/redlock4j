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
