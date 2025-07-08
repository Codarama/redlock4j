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

import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.ScriptOutputType;
import org.codarama.redlock4j.configuration.RedisNodeConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LettuceRedisDriver using Mockito mocks.
 * These tests do not require a working Redis server.
 */
@ExtendWith(MockitoExtension.class)
public class LettuceRedisDriverTest {

    @Mock
    private RedisClient mockRedisClient;

    @Mock
    private StatefulRedisConnection<String, String> mockConnection;

    @Mock
    private RedisCommands<String, String> mockCommands;

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
        driver = new LettuceRedisDriver(testConfig, mockRedisClient, mockConnection, mockCommands);

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

        driver = new LettuceRedisDriver(configWithPassword, mockRedisClient, mockConnection, mockCommands);

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
        
        driver = new LettuceRedisDriver(configWithDb, mockRedisClient, mockConnection, mockCommands);
        
        assertNotNull(driver);
        assertEquals("redis://localhost:6379", driver.getIdentifier());
    }
    
    @Test
    public void testGetIdentifierFormat() {
        // Test identifier format without creating multiple drivers
        driver = new LettuceRedisDriver(testConfig, mockRedisClient, mockConnection, mockCommands);
        assertEquals("redis://localhost:6379", driver.getIdentifier());
        driver.close();
    }
    
    @Test
    public void testDriverCreationWithNullConfig() {
        assertThrows(NullPointerException.class, () ->
            new LettuceRedisDriver(null, mockRedisClient, mockConnection, mockCommands));
    }
    
    @Test
    public void testCloseDoesNotThrowException() {
        driver = new LettuceRedisDriver(testConfig, mockRedisClient, mockConnection, mockCommands);

        // Should not throw exception even if connection fails
        assertDoesNotThrow(() -> driver.close());

        // Verify close was called on mocked dependencies
        verify(mockConnection).close();
        verify(mockRedisClient).shutdown();
    }
    
    @Test
    public void testMultipleCloseCallsAreIdempotent() {
        driver = new LettuceRedisDriver(testConfig, mockRedisClient, mockConnection, mockCommands);

        // Multiple close calls should not throw exceptions
        assertDoesNotThrow(() -> {
            driver.close();
            driver.close();
            driver.close();
        });

        // Verify close was called multiple times
        verify(mockConnection, times(3)).close();
        verify(mockRedisClient, times(3)).shutdown();
    }

    @Test
    public void testSetIfNotExistsSuccess() throws RedisDriverException {
        driver = new LettuceRedisDriver(testConfig, mockRedisClient, mockConnection, mockCommands);

        when(mockCommands.set(eq("test-key"), eq("test-value"), any(SetArgs.class))).thenReturn("OK");

        boolean result = driver.setIfNotExists("test-key", "test-value", 10000);

        assertTrue(result);
        verify(mockCommands).set(eq("test-key"), eq("test-value"), any(SetArgs.class));
    }

    @Test
    public void testSetIfNotExistsFailure() throws RedisDriverException {
        driver = new LettuceRedisDriver(testConfig, mockRedisClient, mockConnection, mockCommands);

        when(mockCommands.set(eq("test-key"), eq("test-value"), any(SetArgs.class))).thenReturn(null);

        boolean result = driver.setIfNotExists("test-key", "test-value", 10000);

        assertFalse(result);
        verify(mockCommands).set(eq("test-key"), eq("test-value"), any(SetArgs.class));
    }

    @Test
    public void testSetIfNotExistsException() {
        driver = new LettuceRedisDriver(testConfig, mockRedisClient, mockConnection, mockCommands);

        when(mockCommands.set(eq("test-key"), eq("test-value"), any(SetArgs.class)))
            .thenThrow(new RuntimeException("Connection failed"));

        RedisDriverException exception = assertThrows(RedisDriverException.class, () -> {
            driver.setIfNotExists("test-key", "test-value", 10000);
        });

        assertTrue(exception.getMessage().contains("Failed to execute SET NX PX command"));
        assertTrue(exception.getMessage().contains("redis://localhost:6379"));
    }

    @Test
    public void testDeleteIfValueMatchesSuccess() throws RedisDriverException {
        driver = new LettuceRedisDriver(testConfig, mockRedisClient, mockConnection, mockCommands);

        when(mockCommands.eval(anyString(), eq(ScriptOutputType.INTEGER), any(String[].class), anyString()))
            .thenReturn(1L);

        boolean result = driver.deleteIfValueMatches("test-key", "test-value");

        assertTrue(result);
        verify(mockCommands).eval(anyString(), eq(ScriptOutputType.INTEGER), any(String[].class), eq("test-value"));
    }

    @Test
    public void testDeleteIfValueMatchesFailure() throws RedisDriverException {
        driver = new LettuceRedisDriver(testConfig, mockRedisClient, mockConnection, mockCommands);

        when(mockCommands.eval(anyString(), eq(ScriptOutputType.INTEGER), any(String[].class), anyString()))
            .thenReturn(0L);

        boolean result = driver.deleteIfValueMatches("test-key", "test-value");

        assertFalse(result);
        verify(mockCommands).eval(anyString(), eq(ScriptOutputType.INTEGER), any(String[].class), eq("test-value"));
    }

    @Test
    public void testDeleteIfValueMatchesException() {
        driver = new LettuceRedisDriver(testConfig, mockRedisClient, mockConnection, mockCommands);

        when(mockCommands.eval(anyString(), eq(ScriptOutputType.INTEGER), any(String[].class), anyString()))
            .thenThrow(new RuntimeException("Script execution failed"));

        RedisDriverException exception = assertThrows(RedisDriverException.class, () -> {
            driver.deleteIfValueMatches("test-key", "test-value");
        });

        assertTrue(exception.getMessage().contains("Failed to execute delete script"));
        assertTrue(exception.getMessage().contains("redis://localhost:6379"));
    }

    @Test
    public void testIsConnectedSuccess() {
        driver = new LettuceRedisDriver(testConfig, mockRedisClient, mockConnection, mockCommands);

        when(mockCommands.ping()).thenReturn("PONG");

        boolean result = driver.isConnected();

        assertTrue(result);
        verify(mockCommands).ping();
    }

    @Test
    public void testIsConnectedFailure() {
        driver = new LettuceRedisDriver(testConfig, mockRedisClient, mockConnection, mockCommands);

        when(mockCommands.ping()).thenThrow(new RuntimeException("Connection failed"));

        boolean result = driver.isConnected();

        assertFalse(result);
        verify(mockCommands).ping();
    }

    @Test
    public void testGetIdentifierWithDifferentConfigurations() {
        RedisNodeConfiguration config1 = RedisNodeConfiguration.builder()
            .host("redis1.example.com")
            .port(6379)
            .build();

        RedisNodeConfiguration config2 = RedisNodeConfiguration.builder()
            .host("redis2.example.com")
            .port(6380)
            .build();

        LettuceRedisDriver driver1 = new LettuceRedisDriver(config1, mockRedisClient, mockConnection, mockCommands);
        LettuceRedisDriver driver2 = new LettuceRedisDriver(config2, mockRedisClient, mockConnection, mockCommands);

        assertEquals("redis://redis1.example.com:6379", driver1.getIdentifier());
        assertEquals("redis://redis2.example.com:6380", driver2.getIdentifier());

        driver1.close();
        driver2.close();
    }
}
