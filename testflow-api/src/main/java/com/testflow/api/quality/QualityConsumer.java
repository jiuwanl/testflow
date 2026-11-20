package com.testflow.api.quality;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "testflow.quality.enabled", havingValue = "true")
public class QualityConsumer {
  private final QualityProjection projection;

  public QualityConsumer(QualityProjection projection) {
    this.projection = projection;
  }

  @KafkaListener(
      id = "qualityProjection",
      autoStartup = "false",
      topics = "${testflow.quality.topic}",
      groupId = "${testflow.quality.group-id}")
  public void receive(ConsumerRecord<String, String> record) {
    projection.apply(record.key(), record.value());
  }
}
