package com.testflow.api.controller;

import com.testflow.api.dto.TargetPolicyDtos;
import com.testflow.api.service.TargetPolicyService;
import com.testflow.api.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/target-policies")
public class TargetPolicyController {
  private final TargetPolicyService service;

  public TargetPolicyController(TargetPolicyService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<?> list(
      @PathVariable long projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.of(service.list(projectId, page, size));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<?> create(
      @PathVariable long projectId, @Valid @RequestBody TargetPolicyDtos.Create input) {
    return ApiResponse.of(service.create(projectId, input));
  }

  @PatchMapping("/{id}")
  public ApiResponse<?> update(
      @PathVariable long projectId,
      @PathVariable long id,
      @Valid @RequestBody TargetPolicyDtos.Patch input) {
    return ApiResponse.of(service.update(projectId, id, input));
  }
}
