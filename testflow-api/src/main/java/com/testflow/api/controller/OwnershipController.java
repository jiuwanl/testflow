package com.testflow.api.controller;

import com.testflow.api.dto.OwnershipDtos;
import com.testflow.api.service.OwnershipGovernanceService;
import com.testflow.api.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/projects")
public class OwnershipController {
  private final OwnershipGovernanceService service;

  public OwnershipController(OwnershipGovernanceService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<?> list(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.of(service.list(page, size));
  }

  @GetMapping("/{id}/ownership")
  public ApiResponse<?> get(@PathVariable long id) {
    return ApiResponse.of(service.get(id));
  }

  @PutMapping("/{id}/ownership")
  public ApiResponse<?> change(
      @PathVariable long id, @Valid @RequestBody OwnershipDtos.Change input) {
    return ApiResponse.of(service.change(id, input));
  }
}
