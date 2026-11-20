package com.testflow.core.quality;

import com.testflow.core.execution.RunMetrics;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Versioned, minimal event. Executable input, credentials and response bodies are absent. */
public record RunFinalized(
    int schemaVersion,
    String eventId,
    String runId,
    String projectId,
    String kind,
    String sourceId,
    String environmentId,
    String finishedAt,
    String state,
    String outcome,
    int total,
    int passed,
    int failed,
    int errors,
    int skipped,
    int cancelled,
    int unknown,
    Long durationMs) {
  public RunFinalized {
    if (schemaVersion != 1) throw new IllegalArgumentException("UNSUPPORTED_EVENT_VERSION");
    if (!UUID.fromString(eventId).toString().equals(eventId))
      throw new IllegalArgumentException("INVALID_EVENT_ID");
    for (String id : new String[] {runId, projectId, sourceId, environmentId})
      if (id == null || !id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id) <= 0)
        throw new IllegalArgumentException("INVALID_EVENT_ID");
    Instant.parse(finishedAt);
    if (!Set.of("CASE", "PLAN").contains(kind)
        || !Set.of("COMPLETED", "CANCELLED", "TIMED_OUT", "INTERRUPTED").contains(state)
        || total < 1
        || total > 100
        || durationMs != null && durationMs < 0)
      throw new IllegalArgumentException("INVALID_EVENT");
    var metrics = new RunMetrics(total, passed, failed, errors, skipped, cancelled, unknown);
    if (passed < 0
        || failed < 0
        || errors < 0
        || skipped < 0
        || cancelled < 0
        || unknown < 0
        || total != (long) passed + failed + errors + skipped + cancelled + unknown
        || !metrics.outcome(state).equals(outcome))
      throw new IllegalArgumentException("INVALID_EVENT_COUNTS");
  }

  @Override
  public String toString() {
    return "RunFinalized[eventId=" + eventId + ",runId=" + runId + "]";
  }
}
