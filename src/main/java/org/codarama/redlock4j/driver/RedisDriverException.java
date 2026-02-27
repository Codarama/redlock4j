/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2025 Codarama
 */
package org.codarama.redlock4j.driver;

/**
 * Exception thrown when there's an error communicating with Redis through a driver.
 */
public class RedisDriverException extends Exception {

    public RedisDriverException(String message) {
        super(message);
    }

    public RedisDriverException(String message, Throwable cause) {
        super(message, cause);
    }

    public RedisDriverException(Throwable cause) {
        super(cause);
    }
}
