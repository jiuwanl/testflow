package com.testflow.api.quality;

import java.util.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableKafka
@ConditionalOnProperty(name = "testflow.quality.enabled", havingValue = "true")
public class QualityKafkaConfig {
  @Bean
  KafkaAdmin kafkaAdmin(@Value("${testflow.quality.bootstrap-servers}") String bootstrap) {
    var admin = new KafkaAdmin(Map.of("bootstrap.servers", bootstrap));
    admin.setAutoCreate(false);
    return admin;
  }

  @Bean
  ProducerFactory<String, String> qualityProducerFactory(
      @Value("${testflow.quality.bootstrap-servers}") String bootstrap) {
    Map<String, Object> values = new HashMap<>();
    values.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    values.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    values.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    values.put(ProducerConfig.ACKS_CONFIG, "all");
    values.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    values.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
    values.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
    values.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
    values.put(ProducerConfig.LINGER_MS_CONFIG, 0);
    values.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 1048576L);
    values.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 32768);
    return new DefaultKafkaProducerFactory<>(values);
  }

  @Bean
  KafkaTemplate<String, String> qualityKafkaTemplate(
      ProducerFactory<String, String> qualityProducerFactory) {
    return new KafkaTemplate<>(qualityProducerFactory);
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      @Value("${testflow.quality.bootstrap-servers}") String bootstrap,
      QualityProjection projection) {
    Map<String, Object> values = new HashMap<>();
    values.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    values.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    values.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    values.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    values.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    values.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
    values.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, 65536);
    values.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 65536);
    values.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(values));
    factory.setConcurrency(1);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
    factory.getContainerProperties().setSyncCommits(true);
    factory.getContainerProperties().setMissingTopicsFatal(false);
    var handler =
        new DefaultErrorHandler(
            (record, error) -> {
              projection.park(
                  record.topic(), record.partition(), record.offset(), (String) record.value());
              org.slf4j.LoggerFactory.getLogger(QualityKafkaConfig.class)
                  .warn(
                      "Quality record parked partition={} offset={}",
                      record.partition(),
                      record.offset());
            },
            new FixedBackOff(500L, 2L));
    handler.setAckAfterHandle(true);
    factory.setCommonErrorHandler(handler);
    return factory;
  }

  @Bean(name = "qualityScheduler")
  ThreadPoolTaskScheduler qualityScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("testflow-outbox-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(8);
    return scheduler;
  }

  @Bean(name = "taskScheduler")
  ThreadPoolTaskScheduler taskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("testflow-maintenance-");
    return scheduler;
  }
}
