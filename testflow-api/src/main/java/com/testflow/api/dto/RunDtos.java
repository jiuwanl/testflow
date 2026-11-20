package com.testflow.api.dto;

import com.testflow.core.execution.RunMetrics;
import com.testflow.persistence.entity.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class RunDtos {
  private RunDtos() {}

  public record Overrides(
      @Size(max = 100)
          Map<
                  @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]{0,63}") String,
                  @NotNull @Size(max = 4096) String>
              variables,
      @Min(1) @Max(60000) Integer requestTimeoutMs) {}

  public record Submit(
      @NotBlank @Pattern(regexp = "CASE|PLAN") String sourceType,
      @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String sourceId,
      @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String environmentId,
      @Valid Overrides overrides) {}

  public record Accepted(String id, String state, String outcome, String detailUrl) {}

  public record Metrics(
      int total,
      int passed,
      int failed,
      int errors,
      int skipped,
      int cancelled,
      int unknown,
      Double progress,
      Double coverage,
      Double passRate,
      Double failureRate,
      Double errorRate) {
    public static Metrics of(RunEntity run) {
      var m =
          new RunMetrics(
              run.getTotalCount(),
              run.getPassedCount(),
              run.getFailedCount(),
              run.getErrorCount(),
              run.getSkippedCount(),
              run.getCancelledCount(),
              run.getUnknownCount());
      return new Metrics(
          m.total(),
          m.passed(),
          m.failed(),
          m.errors(),
          m.skipped(),
          m.cancelled(),
          m.unknown(),
          m.progress(),
          m.coverage(),
          m.passRate(),
          m.failureRate(),
          m.errorRate());
    }
  }

  public record View(
      String id,
      String sourceType,
      String sourceId,
      String parentRunId,
      String sourceName,
      String executionMode,
      int maxConcurrency,
      String environmentId,
      String environmentName,
      String requestedBy,
      String requestedByName,
      String state,
      String outcome,
      String terminalReason,
      Instant createdAt,
      Instant queuedAt,
      Instant startedAt,
      Instant finishedAt,
      Instant deadlineAt,
      Metrics metrics) {
    public static View of(RunEntity run) {
      return new View(
          run.getId().toString(),
          run.getKind(),
          run.getSourceId().toString(),
          run.getParentRunId() == null ? null : run.getParentRunId().toString(),
          run.getSourceNameSnapshot(),
          run.getExecutionMode(),
          run.getMaxConcurrency(),
          run.getEnvId().toString(),
          run.getEnvironmentNameSnapshot(),
          run.getRequestedBy().toString(),
          run.getRequestedByName(),
          run.getState(),
          run.getOutcome(),
          run.getTerminalReason(),
          run.getCreatedAt(),
          run.getQueuedAt(),
          run.getStartedAt(),
          run.getFinishedAt(),
          run.getDeadlineAt(),
          Metrics.of(run));
    }
  }

  public record Item(
      String id,
      int ordinal,
      String caseName,
      String state,
      Long durationMs,
      String errorCode,
      Instant startedAt,
      Instant finishedAt) {
    public static Item of(RunItemEntity item) {
      return new Item(
          item.getId().toString(),
          item.getOrdinal(),
          item.getCaseNameSnapshot(),
          item.getState(),
          item.getDurationMs(),
          item.getErrorCode(),
          item.getStartedAt(),
          item.getFinishedAt());
    }
  }

  public record Detail(Item item, JsonNode result, boolean bodyAvailable, boolean bodyTruncated) {}

  public record Report(View run, List<Item> items) {}
}
