package com.testflow.api.quality;

import com.testflow.core.quality.QualityEventCodec;
import com.testflow.persistence.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class QualityProjection {
  private final OutboxMapper events;
  private final QualityMapper facts;
  private final QualityEventCodec codec = new QualityEventCodec();

  public QualityProjection(OutboxMapper events, QualityMapper facts) {
    this.events = events;
    this.facts = facts;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 2)
  public void apply(String key, String payload) {
    var event = codec.decode(payload);
    if (!event.runId().equals(key)) throw new IllegalArgumentException("EVENT_KEY_MISMATCH");
    var source = events.lock(event.eventId());
    if (source == null || !event.equals(codec.decode(source.payload())))
      throw new IllegalArgumentException("EVENT_SOURCE_MISMATCH");
    String hash = codec.fingerprint(event);
    facts.insert(event.eventId(), hash);
    var existing = facts.fact(Long.parseLong(event.runId()));
    if (existing == null
        || !existing.eventId().equals(event.eventId())
        || !existing.payloadHash().equals(hash))
      throw new IllegalStateException("QUALITY_FACT_CONFLICT");
    facts.resolved(event.eventId());
  }

  @Transactional(timeout = 2)
  public void park(String topic, int partition, long offset, String payload) {
    String id = codec.trustedEventId(payload);
    var source = id == null ? null : events.find(id);
    facts.park(
        topic,
        partition,
        offset,
        source == null ? null : id,
        source == null ? null : source.projectId(),
        codec.hash(payload == null ? "" : payload),
        "PROJECTION_RETRY_EXHAUSTED");
  }
}
