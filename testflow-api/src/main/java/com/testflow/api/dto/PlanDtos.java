package com.testflow.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class PlanDtos {
  private PlanDtos() {}

  public record Step(
      @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String caseId, boolean enabled) {}

  public record Create(
      @NotBlank @Size(max = 100) String name,
      @NotBlank @Pattern(regexp = "ORDERED|INDEPENDENT") String executionMode,
      boolean failFast,
      @Min(1) @Max(4) Integer maxConcurrency,
      @NotNull @Size(max = 100) List<@Valid Step> steps) {}

  public record Patch(
      @NotNull @Min(0) Long version,
      @Size(min = 1, max = 100) String name,
      @Pattern(regexp = "ORDERED|INDEPENDENT") String executionMode,
      Boolean failFast,
      @Min(1) @Max(4) Integer maxConcurrency,
      @Size(max = 100) List<@Valid Step> steps,
      @Pattern(regexp = "ACTIVE|ARCHIVED") String status) {}

  public record View(
      String id,
      String name,
      String executionMode,
      boolean failFast,
      int maxConcurrency,
      List<Step> steps,
      String status,
      long version) {}

  public record Summary(
      String id,
      String name,
      String executionMode,
      boolean failFast,
      int maxConcurrency,
      String status,
      long version) {}
}
