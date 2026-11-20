package com.testflow.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

public final class EnvironmentDtos {
  private EnvironmentDtos() {}

  public record SecretUpdate(
      @NotBlank @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]{0,63}") String name,
      @NotBlank @Pattern(regexp = "SET|REMOVE") String operation,
      @Size(max = 4096) String value) {}

  public record Create(
      @NotBlank @Size(max = 100) String name,
      @NotBlank @Size(max = 2048) String baseUrl,
      @NotNull @Size(max = 100)
          Map<
                  @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]{0,63}") String,
                  @NotNull @Size(max = 4096) String>
              variables,
      @Size(max = 100) List<@Valid SecretUpdate> secretUpdates) {}

  public record Patch(
      @NotNull @Min(0) Long version,
      @Size(min = 1, max = 100) String name,
      @Size(min = 1, max = 2048) String baseUrl,
      @Size(max = 100)
          Map<
                  @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]{0,63}") String,
                  @NotNull @Size(max = 4096) String>
              variables,
      @Size(max = 100) List<@Valid SecretUpdate> secretUpdates,
      @Pattern(regexp = "ACTIVE|ARCHIVED") String status) {}

  public record View(
      String id,
      String name,
      String baseUrl,
      Map<String, String> variables,
      List<String> secretNames,
      String status,
      long version) {}

  public record Summary(String id, String name, String status, long version) {}
}
