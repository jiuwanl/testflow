package com.testflow.api.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public final class OwnershipDtos {
  private OwnershipDtos() {}

  public record Project(String id, String name, String status, long version, int activeOwners) {}

  public record Owner(String userId, String username, String status, long version) {}

  public record Detail(Project project, List<Owner> owners) {}

  public record Change(
      @NotNull @Min(0) Long version,
      @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String successorUserId,
      @Pattern(regexp = "[1-9][0-9]{0,18}") String fromUserId,
      @NotBlank @Size(max = 500) String reason) {}

  public record Transfer(
      @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String projectId,
      @NotNull @Min(0) Long version,
      @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String successorUserId) {}

  public record ImpactProject(
      String id, String name, long version, int remainingActiveOwners, boolean requiresTransfer) {}

  public record Impact(String userId, long version, List<ImpactProject> projects) {}
}
