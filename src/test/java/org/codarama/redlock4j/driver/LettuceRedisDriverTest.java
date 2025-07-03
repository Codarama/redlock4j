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

import org.codarama.redlock4j.configuration.RedisNodeConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LettuceRedisDriver.
 * These tests focus on the driver's public interface and configuration handling.
 */
public class LettuceRedisDriverTest {
    
    private RedisNodeConfiguration testConfig;
    private LettuceRedisDriver driver;
    
    @BeforeEach
    void setUp() {
        testConfig = RedisNodeConfiguration.builder()
            .host("localhost")
            .port(6379)
            .connectionTimeoutMs(5000)
            .socketTimeoutMs(5000)
            .build();
    }
    
    @Test
    public void testDriverCreationWithBasicConfig() {
        driver = new LettuceRedisDriver(testConfig);
        
        assertNotNull(driver);
        assertEquals("redis://localhost:6379", driver.getIdentifier());
    }
    
    @Test
    public void testDriverCreationWithPassword() {
        RedisNodeConfiguration configWithPassword = RedisNodeConfiguration.builder()
            .host("localhost")
            .port(6379)
            .password("testpass")
            .connectionTimeoutMs(5000)
            .socketTimeoutMs(5000)
            .build();
        
        driver = new LettuceRedisDriver(configWithPassword);
        
        assertNotNull(driver);
        assertEquals("redis://localhost:6379", driver.getIdentifier());
    }
    
    @Test
    public void testDriverCreationWithDatabase() {
        RedisNodeConfiguration configWithDb = RedisNodeConfiguration.builder()
            .host("localhost")
            .port(6379)
            .database(2)
            .connectionTimeoutMs(5000)
            .socketTimeoutMs(5000)
            .build();
        
        driver = new LettuceRedisDriver(configWithDb);
        
        assertNotNull(driver);
        assertEquals("redis://localhost:6379", driver.getIdentifier());
    }
    
    @Test
    public void testGetIdentifierFormat() {
        // Test identifier format without creating multiple drivers
        driver = new LettuceRedisDriver(testConfig);
        assertEquals("redis://localhost:6379", driver.getIdentifier());
        driver.close();
    }
    
    @Test
    public void testDriverCreationWithNullConfig() {
        assertThrows(NullPointerException.class, () -> {
            new LettuceRedisDriver(null);
        });
    }
    
    @Test
    public void testCloseDoesNotThrowException() {
        driver = new LettuceRedisDriver(testConfig);
        
        // Should not throw exception even if connection fails
        assertDoesNotThrow(() -> driver.close());
    }
    
    @Test
    public void testMultipleCloseCallsAreIdempotent() {
        driver = new LettuceRedisDriver(testConfig);
        
        // Multiple close calls should not throw exceptions
        assertDoesNotThrow(() -> {
            driver.close();
            driver.close();
            driver.close();
        });
    }
}
