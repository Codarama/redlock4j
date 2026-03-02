/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RedlockException.
 */
@Tag("unit")
public class RedlockExceptionTest {

    @Test
    void shouldCreateExceptionWithMessage() {
        RedlockException exception = new RedlockException("Lock acquisition failed");

        assertEquals("Lock acquisition failed", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    void shouldCreateExceptionWithMessageAndCause() {
        Throwable cause = new RuntimeException("Connection timeout");
        RedlockException exception = new RedlockException("Lock acquisition failed", cause);

        assertEquals("Lock acquisition failed", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void shouldCreateExceptionWithCauseOnly() {
        Throwable cause = new RuntimeException("Connection refused");
        RedlockException exception = new RedlockException(cause);

        assertEquals("java.lang.RuntimeException: Connection refused", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void shouldBeRuntimeException() {
        RedlockException exception = new RedlockException("test");

        assertTrue(exception instanceof RuntimeException);
    }

    @Test
    void shouldPreserveStackTrace() {
        RedlockException exception = new RedlockException("test");

        assertNotNull(exception.getStackTrace());
        assertTrue(exception.getStackTrace().length > 0);
    }

    @Test
    void shouldSupportNestedCauses() {
        Throwable rootCause = new IllegalStateException("Redis not available");
        Throwable intermediateCause = new RuntimeException("Connection failed", rootCause);
        RedlockException exception = new RedlockException("Lock failed", intermediateCause);

        assertSame(intermediateCause, exception.getCause());
        assertSame(rootCause, exception.getCause().getCause());
    }

    @Test
    void shouldHandleNullMessage() {
        RedlockException exception = new RedlockException((String) null);

        assertNull(exception.getMessage());
    }

    @Test
    void shouldHandleEmptyMessage() {
        RedlockException exception = new RedlockException("");

        assertEquals("", exception.getMessage());
    }
}
