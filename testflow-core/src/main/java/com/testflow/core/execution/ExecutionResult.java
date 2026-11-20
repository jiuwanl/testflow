package com.testflow.core.execution;

import java.util.Map;

/** The report contains only explicitly sanitized display data, never the executable input. */
public record ExecutionResult(
    String state,
    String errorCode,
    long durationMs,
    Map<String, Object> report,
    boolean bodyAvailable,
    boolean bodyTruncated,
    Map<String, tools.jackson.databind.JsonNode> outputs,
    java.util.Set<String> outputSecrets) {
  public ExecutionResult(
      String state,
      String errorCode,
      long durationMs,
      Map<String, Object> report,
      boolean bodyAvailable,
      boolean bodyTruncated) {
    this(
        state,
        errorCode,
        durationMs,
        report,
        bodyAvailable,
        bodyTruncated,
        Map.of(),
        java.util.Set.of());
  }

  public ExecutionResult {
    outputs = Map.copyOf(outputs);
    outputSecrets = java.util.Set.copyOf(outputSecrets);
  }

  @Override
  public String toString() {
    return "ExecutionResult[state=" + state + ",errorCode=" + errorCode + "]";
  }

  public static ExecutionResult error(String code, long durationMs) {
    return new ExecutionResult(
        "ERROR", code, durationMs, Map.of("errorCode", code, "bodyAvailable", false), false, false);
  }
}
