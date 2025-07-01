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

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for RedlockConfiguration.
 */
public class RedlockConfigurationTest {
    
    @Test
    public void testBasicConfiguration() {
        RedlockConfiguration config = RedlockConfiguration.builder()
            .addRedisNode("localhost", 6379)
            .addRedisNode("localhost", 6380)
            .addRedisNode("localhost", 6381)
            .build();
        
        assertEquals(3, config.getRedisNodes().size());
        assertEquals(2, config.getQuorum()); // (3/2) + 1 = 2
        assertEquals(TimeUnit.SECONDS.toMillis(30), config.getDefaultLockTimeoutMs());
        assertEquals(200, config.getRetryDelayMs());
        assertEquals(3, config.getMaxRetryAttempts());
        assertEquals(0.01, config.getClockDriftFactor(), 0.001);
    }
    
    @Test
    public void testCustomConfiguration() {
        RedlockConfiguration config = RedlockConfiguration.builder()
            .addRedisNode("redis1", 6379, "password")
            .addRedisNode("redis2", 6379, "password")
            .addRedisNode("redis3", 6379, "password")
            .defaultLockTimeout(60, TimeUnit.SECONDS)
            .retryDelay(500, TimeUnit.MILLISECONDS)
            .maxRetryAttempts(5)
            .clockDriftFactor(0.02)
            .lockAcquisitionTimeout(20, TimeUnit.SECONDS)
            .build();
        
        assertEquals(3, config.getRedisNodes().size());
        assertEquals(TimeUnit.SECONDS.toMillis(60), config.getDefaultLockTimeoutMs());
        assertEquals(500, config.getRetryDelayMs());
        assertEquals(5, config.getMaxRetryAttempts());
        assertEquals(0.02, config.getClockDriftFactor(), 0.001);
        assertEquals(TimeUnit.SECONDS.toMillis(20), config.getLockAcquisitionTimeoutMs());
    }
    
    @Test
    public void testNodeConfiguration() {
        RedisNodeConfiguration nodeConfig = RedisNodeConfiguration.builder()
            .host("redis.example.com")
            .port(6380)
            .password("secret")
            .database(1)
            .connectionTimeoutMs(3000)
            .socketTimeoutMs(3000)
            .build();
        
        assertEquals("redis.example.com", nodeConfig.getHost());
        assertEquals(6380, nodeConfig.getPort());
        assertEquals("secret", nodeConfig.getPassword());
        assertEquals(1, nodeConfig.getDatabase());
        assertEquals(3000, nodeConfig.getConnectionTimeoutMs());
        assertEquals(3000, nodeConfig.getSocketTimeoutMs());
    }
    
    @Test
    public void testValidationErrors() {
        // Test insufficient nodes
        assertThrows(IllegalArgumentException.class, () -> {
            RedlockConfiguration.builder()
                .addRedisNode("localhost", 6379)
                .addRedisNode("localhost", 6380)
                .build(); // Only 2 nodes, need at least 3
        });
        
        // Test no nodes
        assertThrows(IllegalArgumentException.class, () -> {
            RedlockConfiguration.builder().build();
        });
        
        // Test invalid timeout
        assertThrows(IllegalArgumentException.class, () -> {
            RedlockConfiguration.builder()
                .addRedisNode("localhost", 6379)
                .addRedisNode("localhost", 6380)
                .addRedisNode("localhost", 6381)
                .defaultLockTimeout(-1, TimeUnit.SECONDS)
                .build();
        });
        
        // Test invalid clock drift factor
        assertThrows(IllegalArgumentException.class, () -> {
            RedlockConfiguration.builder()
                .addRedisNode("localhost", 6379)
                .addRedisNode("localhost", 6380)
                .addRedisNode("localhost", 6381)
                .clockDriftFactor(1.5) // > 1.0
                .build();
        });
    }
    
    @Test
    public void testNodeConfigurationValidation() {
        // Test invalid host
        assertThrows(IllegalArgumentException.class, () -> {
            RedisNodeConfiguration.builder()
                .host("")
                .build();
        });
        
        // Test invalid port
        assertThrows(IllegalArgumentException.class, () -> {
            RedisNodeConfiguration.builder()
                .host("localhost")
                .port(0)
                .build();
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            RedisNodeConfiguration.builder()
                .host("localhost")
                .port(70000) // > 65535
                .build();
        });
    }
    
    @Test
    public void testQuorumCalculation() {
        // Test with 3 nodes
        RedlockConfiguration config3 = RedlockConfiguration.builder()
            .addRedisNode("localhost", 6379)
            .addRedisNode("localhost", 6380)
            .addRedisNode("localhost", 6381)
            .build();
        assertEquals(2, config3.getQuorum());
        
        // Test with 5 nodes
        RedlockConfiguration config5 = RedlockConfiguration.builder()
            .addRedisNode("localhost", 6379)
            .addRedisNode("localhost", 6380)
            .addRedisNode("localhost", 6381)
            .addRedisNode("localhost", 6382)
            .addRedisNode("localhost", 6383)
            .build();
        assertEquals(3, config5.getQuorum());
    }
}
