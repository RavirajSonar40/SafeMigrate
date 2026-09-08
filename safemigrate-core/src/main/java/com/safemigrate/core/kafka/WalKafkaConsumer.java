package com.safemigrate.core.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.safemigrate.core.model.WalChangeEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Consumes WAL change events from Kafka for replaying onto the shadow table.
 */
public class WalKafkaConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WalKafkaConsumer.class);

    private final KafkaConsumer<String, String> consumer;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread consumerThread;

    public WalKafkaConsumer(String bootstrapServers, String groupId, String tableName) {
        this.topic = "safemigrate.wal." + tableName.toLowerCase();
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); // Manual commit after apply

        this.consumer = new KafkaConsumer<>(props);
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Starts background consumption loop, invoking eventHandler for each record.
     */
    public void start(Consumer<WalChangeEvent> eventHandler) {
        if (running.get()) {
            return;
        }
        running.set(true);
        consumer.subscribe(Collections.singletonList(topic));

        consumerThread = Thread.ofVirtual().name("kafka-consumer-" + topic).start(() -> {
            log.info("Started Kafka consumer on topic '{}'", topic);
            try {
                while (running.get()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<String, String> record : records) {
                        try {
                            WalChangeEvent event = objectMapper.readValue(record.value(), WalChangeEvent.class);
                            eventHandler.accept(event);
                        } catch (Exception e) {
                            log.error("Failed to process Kafka record at offset {}: {}",
                                    record.offset(), e.getMessage(), e);
                        }
                    }
                    if (!records.isEmpty()) {
                        consumer.commitSync();
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Kafka consumer loop error: {}", e.getMessage(), e);
                }
            } finally {
                log.info("Kafka consumer loop on '{}' stopped.", topic);
            }
        });
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (consumerThread != null) {
                consumerThread.interrupt();
            }
            consumer.wakeup();
        }
    }

    @Override
    public void close() {
        stop();
        consumer.close();
    }
}
