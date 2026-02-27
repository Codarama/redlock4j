/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j;

/**
 * Exception thrown by Redlock operations.
 */
public class RedlockException extends RuntimeException {

    public RedlockException(String message) {
        super(message);
    }

    public RedlockException(String message, Throwable cause) {
        super(message, cause);
    }

    public RedlockException(Throwable cause) {
        super(cause);
    }
}
