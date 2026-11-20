package com.testflow.api.dto;

import com.testflow.persistence.entity.ApiCaseEntity;
import jakarta.validation.constraints.*;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class CaseDtos {
  private CaseDtos() {}

  public record Create(
      @NotBlank @Size(max = 100) String name,
      @Size(max = 20) List<@NotBlank @Size(max = 32) String> tags,
      @Pattern(regexp = "P0|P1|P2|P3") String priority,
      @Min(1) @Max(1) int configSchemaVersion,
      @NotNull JsonNode config) {}

  public record Patch(
      @NotNull @Min(0) Long version,
      @Size(min = 1, max = 100) String name,
      @Size(max = 20) List<@NotBlank @Size(max = 32) String> tags,
      @Pattern(regexp = "P0|P1|P2|P3") String priority,
      @Min(1) @Max(1) Integer configSchemaVersion,
      JsonNode config,
      @Pattern(regexp = "ACTIVE|ARCHIVED") String status) {}

  public record View(
      String id,
      String name,
      List<String> tags,
      String priority,
      int configSchemaVersion,
      JsonNode config,
      String status,
      long version) {}

  public record Summary(String id, String name, String priority, String status, long version) {
    public static Summary from(ApiCaseEntity e) {
      return new Summary(
          e.getId().toString(), e.getName(), e.getPriority(), e.getStatus(), e.getVersion());
    }
  }
}
