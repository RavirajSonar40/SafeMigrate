package com.safemigrate.core.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Automates dynamic creation and inspection of Kafka topics for migration WAL streams.
 */
public class KafkaTopicManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaTopicManager.class);

    private final AdminClient adminClient;

    public KafkaTopicManager(String bootstrapServers) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        this.adminClient = AdminClient.create(props);
    }

    /**
     * Ensures the WAL topic for a specific table exists with the specified number of partitions.
     */
    public String ensureTopicExists(String tableName, int numPartitions, short replicationFactor) {
        String topicName = "safemigrate.wal." + tableName.toLowerCase();
        try {
            Set<String> existingTopics = adminClient.listTopics().names().get();
            if (!existingTopics.contains(topicName)) {
                log.info("Creating Kafka topic '{}' with {} partition(s)...", topicName, numPartitions);
                NewTopic newTopic = new NewTopic(topicName, numPartitions, replicationFactor);
                adminClient.createTopics(Collections.singleton(newTopic)).all().get();
                log.info("Topic '{}' created successfully.", topicName);
            } else {
                log.info("Kafka topic '{}' already exists.", topicName);
            }
            return topicName;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to ensure Kafka topic '{}' exists: {}", topicName, e.getMessage(), e);
            throw new RuntimeException("Kafka topic initialization failed for " + topicName, e);
        }
    }

    @Override
    public void close() {
        adminClient.close();
    }
}
