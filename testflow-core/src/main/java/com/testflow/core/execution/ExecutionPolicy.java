package com.testflow.core.execution;

import tools.jackson.databind.JsonNode;

/** Shared finite execution modes; independent items never consume another item's outputs. */
public final class ExecutionPolicy {
  private ExecutionPolicy() {}

  public static void validate(String mode, int concurrency) {
    if (!("ORDERED".equals(mode) && concurrency == 1)
        && !("INDEPENDENT".equals(mode) && concurrency >= 1 && concurrency <= 4))
      throw new IllegalArgumentException("ORDERED 并发度必须为 1；INDEPENDENT 并发度范围为 1～4");
  }

  public static void independent(JsonNode config) {
    if (config.isTextual() && config.asString().contains("${run."))
      throw new IllegalArgumentException("独立计划不能引用运行变量，请使用环境变量或有序计划");
    if (config.isObject())
      for (var field : config.properties()) {
        if (field.getKey().contains("${run.")) throw new IllegalArgumentException("独立计划不能引用运行变量");
        independent(field.getValue());
      }
    else if (config.isArray()) config.forEach(ExecutionPolicy::independent);
  }
}
