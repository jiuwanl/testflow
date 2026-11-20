package com.testflow.api.quality;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxPublisherTest {
  @SuppressWarnings("unchecked")
  @Test
  void backsOffEmptyHistoricalScanWhileStillPollingNewOutboxEvents() {
    var store = mock(OutboxStore.class);
    var publisher =
        new OutboxPublisher(store, mock(KafkaTemplate.class), "testflow.run-finalized.v1");
    publisher.publish();
    publisher.publish();
    verify(store, times(1)).backfill(null, 20);
    verify(store, times(2)).claim();
  }

  @SuppressWarnings("unchecked")
  @Test
  void drainsFullBackfillBatchesWithoutWaitingForTheIdleInterval() {
    var store = mock(OutboxStore.class);
    when(store.backfill(null, 20)).thenReturn(20, 0);
    var publisher =
        new OutboxPublisher(store, mock(KafkaTemplate.class), "testflow.run-finalized.v1");
    publisher.publish();
    publisher.publish();
    publisher.publish();
    verify(store, times(2)).backfill(null, 20);
    verify(store, times(3)).claim();
  }
}
