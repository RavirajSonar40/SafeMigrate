package com.safemigrate.server.config;

import com.safemigrate.core.kafka.KafkaTopicManager;
import com.safemigrate.core.state.StateStore;
import com.safemigrate.core.wal.PostgresReplicationConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class SafeMigrateConfig {

    private final SafeMigrateProperties properties;

    public SafeMigrateConfig(SafeMigrateProperties properties) {
        this.properties = properties;
    }

    @Bean(destroyMethod = "close")
    public StateStore stateStore() {
        return new StateStore(properties.getRedis().getAddress());
    }

    @Bean(destroyMethod = "close")
    public KafkaTopicManager kafkaTopicManager() {
        return new KafkaTopicManager(properties.getKafka().getBootstrapServers());
    }

    @Bean
    public PostgresReplicationConnectionFactory repConnFactory() {
        return new PostgresReplicationConnectionFactory(
                properties.getTargetDb().getUrl(),
                properties.getTargetDb().getUsername(),
                properties.getTargetDb().getPassword()
        );
    }

    @Bean(name = "migrationExecutor", destroyMethod = "shutdown")
    public ExecutorService migrationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
