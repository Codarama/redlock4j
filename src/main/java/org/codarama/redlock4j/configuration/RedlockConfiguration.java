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
package org.codarama.redlock4j.configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Central configuration class for Redlock using builder pattern.
 */
public class RedlockConfiguration {
    private final List<RedisNodeConfiguration> redisNodes;
    private final long defaultLockTimeoutMs;
    private final long retryDelayMs;
    private final int maxRetryAttempts;
    private final double clockDriftFactor;
    private final long lockAcquisitionTimeoutMs;

    private RedlockConfiguration(Builder builder) {
        this.redisNodes = new ArrayList<>(builder.redisNodes);
        this.defaultLockTimeoutMs = builder.defaultLockTimeoutMs;
        this.retryDelayMs = builder.retryDelayMs;
        this.maxRetryAttempts = builder.maxRetryAttempts;
        this.clockDriftFactor = builder.clockDriftFactor;
        this.lockAcquisitionTimeoutMs = builder.lockAcquisitionTimeoutMs;
    }

    public List<RedisNodeConfiguration> getRedisNodes() {
        return new ArrayList<>(redisNodes);
    }

    public long getDefaultLockTimeoutMs() {
        return defaultLockTimeoutMs;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public int getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    public double getClockDriftFactor() {
        return clockDriftFactor;
    }

    public long getLockAcquisitionTimeoutMs() {
        return lockAcquisitionTimeoutMs;
    }

    public int getQuorum() {
        return redisNodes.size() / 2 + 1;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<RedisNodeConfiguration> redisNodes = new ArrayList<>();
        private long defaultLockTimeoutMs = Duration.ofSeconds(30).toMillis();
        private long retryDelayMs = 200;
        private int maxRetryAttempts = 3;
        private double clockDriftFactor = 0.01;
        private long lockAcquisitionTimeoutMs = Duration.ofSeconds(10).toMillis();

        public Builder addRedisNode(RedisNodeConfiguration nodeConfig) {
            this.redisNodes.add(nodeConfig);
            return this;
        }

        public Builder addRedisNode(String host, int port) {
            return addRedisNode(RedisNodeConfiguration.builder()
                    .host(host)
                    .port(port)
                    .build());
        }

        public Builder addRedisNode(String host, int port, String password) {
            return addRedisNode(RedisNodeConfiguration.builder()
                    .host(host)
                    .port(port)
                    .password(password)
                    .build());
        }

        public Builder defaultLockTimeout(Duration timeout) {
            this.defaultLockTimeoutMs = timeout.toMillis();
            return this;
        }

        public Builder retryDelay(Duration delay) {
            this.retryDelayMs = delay.toMillis();
            return this;
        }

        public Builder maxRetryAttempts(int maxRetryAttempts) {
            this.maxRetryAttempts = maxRetryAttempts;
            return this;
        }

        public Builder clockDriftFactor(double clockDriftFactor) {
            this.clockDriftFactor = clockDriftFactor;
            return this;
        }

        public Builder lockAcquisitionTimeout(Duration timeout) {
            this.lockAcquisitionTimeoutMs = timeout.toMillis();
            return this;
        }

        public RedlockConfiguration build() {
            if (redisNodes.isEmpty()) {
                throw new IllegalArgumentException("At least one Redis node must be configured");
            }
            if (redisNodes.size() < 3) {
                throw new IllegalArgumentException("Redlock requires at least 3 Redis nodes for proper operation");
            }
            if (defaultLockTimeoutMs <= 0) {
                throw new IllegalArgumentException("Default lock timeout must be positive");
            }
            if (retryDelayMs < 0) {
                throw new IllegalArgumentException("Retry delay cannot be negative");
            }
            if (maxRetryAttempts < 0) {
                throw new IllegalArgumentException("Max retry attempts cannot be negative");
            }
            if (clockDriftFactor < 0 || clockDriftFactor > 1) {
                throw new IllegalArgumentException("Clock drift factor must be between 0 and 1");
            }
            return new RedlockConfiguration(this);
        }
    }
}
