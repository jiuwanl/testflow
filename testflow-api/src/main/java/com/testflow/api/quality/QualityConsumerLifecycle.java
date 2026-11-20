package com.testflow.api.quality;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A stopped Docker broker has no DNS entry; consumer construction must not fail API startup. */
@Component
@ConditionalOnProperty(name = "testflow.quality.enabled", havingValue = "true")
public class QualityConsumerLifecycle {
  private final KafkaListenerEndpointRegistry registry;
  private final ApplicationAvailability availability;

  public QualityConsumerLifecycle(
      KafkaListenerEndpointRegistry registry, ApplicationAvailability availability) {
    this.registry = registry;
    this.availability = availability;
  }

  @Scheduled(scheduler = "qualityScheduler", fixedDelay = 5000, initialDelay = 5000)
  public void startWhenAvailable() {
    if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) return;
    var listener = registry.getListenerContainer("qualityProjection");
    if (listener == null || listener.isRunning()) return;
    try {
      listener.start();
    } catch (RuntimeException error) {
      // ConcurrentMessageListenerContainer sets its running flag before constructing children.
      // Stop a partial start so the next tick can actually retry and no child is orphaned.
      try {
        listener.stop();
      } catch (RuntimeException cleanup) {
        LoggerFactory.getLogger(getClass())
            .warn("Quality consumer cleanup deferred type={}", cleanup.getClass().getSimpleName());
      }
      LoggerFactory.getLogger(getClass())
          .warn("Quality consumer startup deferred type={}", error.getClass().getSimpleName());
    }
  }
}
