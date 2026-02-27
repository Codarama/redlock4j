/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LockResult value object.
 */
public class LockResultTest {

    @Test
    void shouldStoreAcquiredState() {
        LockResult result = new LockResult(true, 5000L, "lock-value-123", 2, 3);

        assertTrue(result.isAcquired());
    }

    @Test
    void shouldStoreNotAcquiredState() {
        LockResult result = new LockResult(false, 0L, "lock-value-123", 1, 3);

        assertFalse(result.isAcquired());
    }

    @Test
    void shouldStoreValidityTime() {
        LockResult result = new LockResult(true, 29500L, "lock-value-123", 3, 3);

        assertEquals(29500L, result.getValidityTimeMs());
    }

    @Test
    void shouldStoreLockValue() {
        LockResult result = new LockResult(true, 5000L, "unique-lock-value", 2, 3);

        assertEquals("unique-lock-value", result.getLockValue());
    }

    @Test
    void shouldStoreNodeCounts() {
        LockResult result = new LockResult(true, 5000L, "lock-value", 2, 3);

        assertEquals(2, result.getSuccessfulNodes());
        assertEquals(3, result.getTotalNodes());
    }

    @Test
    void shouldHandleAllNodesSuccess() {
        LockResult result = new LockResult(true, 5000L, "lock-value", 5, 5);

        assertEquals(5, result.getSuccessfulNodes());
        assertEquals(5, result.getTotalNodes());
    }

    @Test
    void shouldHandleZeroValidityTime() {
        LockResult result = new LockResult(false, 0L, "lock-value", 1, 3);

        assertEquals(0L, result.getValidityTimeMs());
    }

    @Test
    void shouldHandleNullLockValue() {
        LockResult result = new LockResult(false, 0L, null, 0, 3);

        assertNull(result.getLockValue());
    }

    @Test
    void toStringShouldContainAllFields() {
        LockResult result = new LockResult(true, 5000L, "lock-value", 2, 3);
        String str = result.toString();

        assertTrue(str.contains("acquired=true"));
        assertTrue(str.contains("validityTimeMs=5000"));
        assertTrue(str.contains("successfulNodes=2"));
        assertTrue(str.contains("totalNodes=3"));
    }

    @Test
    void toStringShouldWorkForFailedAcquisition() {
        LockResult result = new LockResult(false, 0L, "lock-value", 1, 3);
        String str = result.toString();

        assertTrue(str.contains("acquired=false"));
        assertTrue(str.contains("validityTimeMs=0"));
    }
}
