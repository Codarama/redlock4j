package org.codarama.redlock4j;

/**
 * Configuration for a single Redis node in the Redlock cluster.
 */
public class RedisNodeConfiguration {
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final int connectionTimeoutMs;
    private final int socketTimeoutMs;

    private RedisNodeConfiguration(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.password = builder.password;
        this.database = builder.database;
        this.connectionTimeoutMs = builder.connectionTimeoutMs;
        this.socketTimeoutMs = builder.socketTimeoutMs;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getPassword() {
        return password;
    }

    public int getDatabase() {
        return database;
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public int getSocketTimeoutMs() {
        return socketTimeoutMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String host = "localhost";
        private int port = 6379;
        private String password;
        private int database = 0;
        private int connectionTimeoutMs = 2000;
        private int socketTimeoutMs = 2000;

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder database(int database) {
            this.database = database;
            return this;
        }

        public Builder connectionTimeoutMs(int connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
            return this;
        }

        public Builder socketTimeoutMs(int socketTimeoutMs) {
            this.socketTimeoutMs = socketTimeoutMs;
            return this;
        }

        public RedisNodeConfiguration build() {
            if (host == null || host.trim().isEmpty()) {
                throw new IllegalArgumentException("Host cannot be null or empty");
            }
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535");
            }
            return new RedisNodeConfiguration(this);
        }
    }

    @Override
    public String toString() {
        return "RedisNodeConfiguration{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", database=" + database +
                ", connectionTimeoutMs=" + connectionTimeoutMs +
                ", socketTimeoutMs=" + socketTimeoutMs +
                '}';
    }
}
