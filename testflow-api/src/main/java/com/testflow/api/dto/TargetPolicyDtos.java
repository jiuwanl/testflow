package com.testflow.api.dto;

import jakarta.validation.constraints.*;

public final class TargetPolicyDtos {
  private TargetPolicyDtos() {}

  public record Create(
      @NotBlank @Pattern(regexp = "http|https") String scheme,
      @NotBlank @Size(max = 253) String host,
      @Min(1) @Max(65535) int port,
      @NotBlank @Pattern(regexp = "PUBLIC|PRIVATE|LOOPBACK") String addressScope) {}

  public record Patch(@NotNull @Min(0) Long version, @NotNull Boolean enabled) {}

  public record View(
      String id,
      String scheme,
      String host,
      int port,
      String addressScope,
      boolean enabled,
      long version) {}
}
