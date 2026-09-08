package com.safemigrate.core.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safemigrate.core.model.WalChangeEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.Future;

/**
 * Publishes captured WAL change events to Kafka, partitioned by row primary key.
 */
public class WalKafkaProducer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WalKafkaProducer.class);

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper;
    private final String pkColumn;

    public WalKafkaProducer(String bootstrapServers, String pkColumn) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all"); // Full durability
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true"); // Exactly-once producer semantics
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5); // Batch small bursts

        this.producer = new KafkaProducer<>(props);
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.pkColumn = pkColumn;
    }

    /**
     * Publishes a WalChangeEvent to Kafka. The message key is derived from the primary key,
     * ensuring that all changes for a specific row are routed to the same partition for strict ordering.
     */
    public Future<RecordMetadata> send(WalChangeEvent event) {
        try {
            String topic = "safemigrate.wal." + event.table().toLowerCase();
            Object pkVal = event.getPrimaryKeyValue(pkColumn);
            String messageKey = pkVal != null ? String.valueOf(pkVal) : "default_key";
            String jsonPayload = objectMapper.writeValueAsString(event);

            ProducerRecord<String, String> record = new ProducerRecord<>(topic, messageKey, jsonPayload);
            return producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to deliver WAL event for key {} to topic {}: {}",
                            messageKey, topic, exception.getMessage());
                } else {
                    log.trace("Delivered event for key {} to partition {} at offset {}",
                            messageKey, metadata.partition(), metadata.offset());
                }
            });
        } catch (Exception e) {
            log.error("Error serializing WAL change event: {}", e.getMessage(), e);
            throw new RuntimeException("Serialization failure in WalKafkaProducer", e);
        }
    }

    public void flush() {
        producer.flush();
    }

    @Override
    public void close() {
        producer.flush();
        producer.close();
    }
}
