package com.testflow.core.execution;

import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Frozen business inputs. Current authorization is checked separately before connecting. */
public record ExecutionSnapshot(
    int schemaVersion,
    long runId,
    long projectId,
    long requestedBy,
    long environmentId,
    long environmentVersion,
    String baseUrl,
    Map<String, String> variables,
    Set<String> secretNames,
    List<TargetRule> approvedTargets,
    List<Step> steps,
    boolean failFast,
    Long planVersion,
    String executionMode,
    int maxConcurrency) {
  public ExecutionSnapshot(
      int schemaVersion,
      long runId,
      long projectId,
      long requestedBy,
      long environmentId,
      long environmentVersion,
      String baseUrl,
      Map<String, String> variables,
      Set<String> secretNames,
      List<TargetRule> approvedTargets,
      List<Step> steps,
      boolean failFast,
      Long planVersion) {
    this(
        schemaVersion,
        runId,
        projectId,
        requestedBy,
        environmentId,
        environmentVersion,
        baseUrl,
        variables,
        secretNames,
        approvedTargets,
        steps,
        failFast,
        planVersion,
        "ORDERED",
        1);
  }

  public ExecutionSnapshot(
      int schemaVersion,
      long runId,
      long projectId,
      long requestedBy,
      long environmentId,
      long environmentVersion,
      String baseUrl,
      Map<String, String> variables,
      Set<String> secretNames,
      List<TargetRule> approvedTargets,
      List<Step> steps) {
    this(
        schemaVersion,
        runId,
        projectId,
        requestedBy,
        environmentId,
        environmentVersion,
        baseUrl,
        variables,
        secretNames,
        approvedTargets,
        steps,
        false,
        null);
  }

  public ExecutionSnapshot {
    variables = Map.copyOf(variables);
    secretNames = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(secretNames));
    approvedTargets = List.copyOf(approvedTargets);
    steps = List.copyOf(steps);
  }

  public record TargetRule(long id, String scheme, String host, int port, String addressScope) {}

  public record Step(long caseId, String name, long version, JsonNode config) {
    public Step {
      config = config.deepCopy();
    }
  }
}
