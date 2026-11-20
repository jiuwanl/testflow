package com.testflow.api.dto;

import com.testflow.persistence.entity.*;
import jakarta.validation.constraints.*;

public final class ProjectDtos {
  private ProjectDtos() {}

  public record Create(
      @NotBlank @Size(max = 100) String name,
      @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String ownerId) {}

  public record Patch(
      @NotNull @Min(0) Long version,
      @Size(min = 1, max = 100) String name,
      @Pattern(regexp = "ACTIVE|ARCHIVED") String status) {}

  public record MemberChange(
      @NotBlank @Pattern(regexp = "OWNER|MAINTAINER|VIEWER") String role, @Min(0) Long version) {}

  public record View(String id, String name, String status, String role, long version) {
    public static View from(ProjectEntity p, String role) {
      return new View(p.getId().toString(), p.getName(), p.getStatus(), role, p.getVersion());
    }
  }

  public record MemberView(
      String userId, String username, String status, String role, long version) {
    public static MemberView from(MemberEntity m) {
      return new MemberView(
          m.getUserId().toString(),
          m.getUsername(),
          m.getUserStatus(),
          m.getRole(),
          m.getVersion());
    }
  }
}
