package com.safemigrate.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "safemigrate")
public class SafeMigrateProperties {

    private TargetDb targetDb = new TargetDb();
    private Kafka kafka = new Kafka();
    private Redis redis = new Redis();
    private Migration migration = new Migration();

    public TargetDb getTargetDb() {
        return targetDb;
    }

    public void setTargetDb(TargetDb targetDb) {
        this.targetDb = targetDb;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }

    public Migration getMigration() {
        return migration;
    }

    public void setMigration(Migration migration) {
        this.migration = migration;
    }

    public static class TargetDb {
        private String url = "jdbc:postgresql://localhost:5432/safemigrate_test";
        private String username = "postgres";
        private String password = "password";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }
    }

    public static class Redis {
        private String address = "redis://localhost:6380";

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }
    }

    public static class Migration {
        private int defaultBatchSize = 100;
        private long defaultThrottleDelayMs = 10;
        private long maxLockTimeoutMs = 2000;
        private boolean approvalRequired = true;
        private long maxAllowedLagBytes = 65536; // 64 KB

        public int getDefaultBatchSize() {
            return defaultBatchSize;
        }

        public void setDefaultBatchSize(int defaultBatchSize) {
            this.defaultBatchSize = defaultBatchSize;
        }

        public long getDefaultThrottleDelayMs() {
            return defaultThrottleDelayMs;
        }

        public void setDefaultThrottleDelayMs(long defaultThrottleDelayMs) {
            this.defaultThrottleDelayMs = defaultThrottleDelayMs;
        }

        public long getMaxLockTimeoutMs() {
            return maxLockTimeoutMs;
        }

        public void setMaxLockTimeoutMs(long maxLockTimeoutMs) {
            this.maxLockTimeoutMs = maxLockTimeoutMs;
        }

        public boolean isApprovalRequired() {
            return approvalRequired;
        }

        public void setApprovalRequired(boolean approvalRequired) {
            this.approvalRequired = approvalRequired;
        }

        public long getMaxAllowedLagBytes() {
            return maxAllowedLagBytes;
        }

        public void setMaxAllowedLagBytes(long maxAllowedLagBytes) {
            this.maxAllowedLagBytes = maxAllowedLagBytes;
        }
    }
}
