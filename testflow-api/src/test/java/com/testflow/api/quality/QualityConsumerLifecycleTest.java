package com.testflow.api.quality;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.*;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

class QualityConsumerLifecycleTest {
  @Test
  void unresolvedBrokerCannotFailApiAndStartupRetriesWithoutDuplicateContainers() {
    var registry = mock(KafkaListenerEndpointRegistry.class);
    var availability = mock(ApplicationAvailability.class);
    var listener = mock(MessageListenerContainer.class);
    when(registry.getListenerContainer("qualityProjection")).thenReturn(listener);
    when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
    var running = new java.util.concurrent.atomic.AtomicBoolean();
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    when(listener.isRunning()).thenAnswer(invocation -> running.get());
    doAnswer(
            invocation -> {
              running.set(true);
              if (attempts.getAndIncrement() == 0)
                throw new IllegalStateException("synthetic DNS unavailable");
              return null;
            })
        .when(listener)
        .start();
    doAnswer(
            invocation -> {
              running.set(false);
              return null;
            })
        .when(listener)
        .stop();
    var lifecycle = new QualityConsumerLifecycle(registry, availability);
    assertThatCode(lifecycle::startWhenAvailable).doesNotThrowAnyException();
    lifecycle.startWhenAvailable();
    lifecycle.startWhenAvailable();
    verify(listener, times(2)).start();
    verify(listener).stop();
  }

  @Test
  void doesNotStartConsumersBeforeReadinessOrDuringShutdown() {
    var registry = mock(KafkaListenerEndpointRegistry.class);
    var availability = mock(ApplicationAvailability.class);
    when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
    new QualityConsumerLifecycle(registry, availability).startWhenAvailable();
    verifyNoInteractions(registry);
  }
}
