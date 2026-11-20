package com.testflow.api.dto;

import com.testflow.api.dto.OwnershipDtos.Transfer;
import com.testflow.persistence.entity.UserEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class UserDtos {
  private UserDtos() {}

  public record Login(
      @NotBlank @Size(max = 32) String username, @NotBlank @Size(max = 72) String password) {}

  public record Create(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{2,31}") String username,
      @NotBlank @Size(min = 12, max = 72) String password,
      boolean globalAdmin) {}

  public record Patch(
      @NotNull @Min(0) Long version,
      @Pattern(regexp = "ACTIVE|DISABLED") String status,
      @Size(min = 12, max = 72) String password,
      @Size(max = 1000) List<@NotNull @Valid Transfer> ownershipTransfers) {}

  public record View(String id, String username, boolean globalAdmin, String status, long version) {
    public static View from(UserEntity u) {
      return new View(
          u.getId().toString(), u.getUsername(), u.isGlobalAdmin(), u.getStatus(), u.getVersion());
    }
  }
}
