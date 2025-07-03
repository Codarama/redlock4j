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
package org.codarama.redlock4j.driver;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RedisDriverException.
 */
public class RedisDriverExceptionTest {
    
    @Test
    public void testConstructorWithMessage() {
        String message = "Test error message";
        RedisDriverException exception = new RedisDriverException(message);
        
        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause());
    }
    
    @Test
    public void testConstructorWithMessageAndCause() {
        String message = "Test error message";
        RuntimeException cause = new RuntimeException("Root cause");
        RedisDriverException exception = new RedisDriverException(message, cause);
        
        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
    }
    
    @Test
    public void testConstructorWithCause() {
        RuntimeException cause = new RuntimeException("Root cause");
        RedisDriverException exception = new RedisDriverException(cause);
        
        assertEquals(cause, exception.getCause());
        // Message should be the cause's toString()
        assertEquals(cause.toString(), exception.getMessage());
    }
    
    @Test
    public void testExceptionIsCheckedException() {
        // RedisDriverException should extend Exception (checked exception)
        assertTrue(Exception.class.isAssignableFrom(RedisDriverException.class));
        assertFalse(RuntimeException.class.isAssignableFrom(RedisDriverException.class));
    }
    
    @Test
    public void testExceptionCanBeThrown() {
        assertThrows(RedisDriverException.class, () -> {
            throw new RedisDriverException("Test exception");
        });
    }
    
    @Test
    public void testExceptionWithNullMessage() {
        RedisDriverException exception = new RedisDriverException((String) null);
        assertNull(exception.getMessage());
    }
    
    @Test
    public void testExceptionWithNullCause() {
        RedisDriverException exception = new RedisDriverException((Throwable) null);
        assertNull(exception.getCause());
    }
    
    @Test
    public void testExceptionWithEmptyMessage() {
        String message = "";
        RedisDriverException exception = new RedisDriverException(message);
        
        assertEquals(message, exception.getMessage());
        assertNull(exception.getCause());
    }
    
    @Test
    public void testExceptionInheritance() {
        RedisDriverException exception = new RedisDriverException("Test");

        assertTrue(exception instanceof Exception);
        assertTrue(exception instanceof Throwable);
        // RedisDriverException extends Exception, not RuntimeException
        assertFalse(RuntimeException.class.isAssignableFrom(RedisDriverException.class));
    }
    
    @Test
    public void testExceptionSerialization() {
        // Test that the exception can be serialized (implements Serializable through Exception)
        RedisDriverException exception = new RedisDriverException("Test message", new RuntimeException("cause"));
        
        // Basic check that it has the serialization capability
        assertTrue(java.io.Serializable.class.isAssignableFrom(RedisDriverException.class));
    }
}
