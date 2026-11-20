package com.testflow.api.quality;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "testflow.quality.enabled", havingValue = "true")
public class OutboxPublisher {
  private final OutboxStore store;
  private final KafkaTemplate<String, String> kafka;
  private final String topic;
  private long nextBackfillNanos = System.nanoTime();

  public OutboxPublisher(
      OutboxStore store,
      KafkaTemplate<String, String> kafka,
      @Value("${testflow.quality.topic}") String topic) {
    this.store = store;
    this.kafka = kafka;
    this.topic = topic;
    if (!topic.matches("[A-Za-z0-9._-]{1,100}"))
      throw new IllegalArgumentException("Invalid quality topic");
  }

  @Scheduled(
      scheduler = "qualityScheduler",
      fixedDelayString = "${testflow.quality.publish-interval-ms:1000}",
      initialDelay = 1500)
  public void publish() {
    try {
      if (System.nanoTime() - nextBackfillNanos >= 0) {
        int added = store.backfill(null, 20);
        nextBackfillNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(added == 20 ? 0 : 60);
      }
      for (int n = 0; n < 10; n++) {
        var event = store.claim();
        if (event == null) return;
        try {
          kafka.send(topic, Long.toString(event.runId()), event.payload()).get(5, TimeUnit.SECONDS);
          store.published(event);
        } catch (Exception error) {
          if (error instanceof InterruptedException) Thread.currentThread().interrupt();
          store.failed(event);
          return;
        }
      }
    } catch (RuntimeException error) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Outbox processing unavailable type={}", error.getClass().getSimpleName());
    }
  }
}
