package com.testflow.api.controller;

import com.testflow.api.dto.EnvironmentDtos;
import com.testflow.api.service.EnvironmentService;
import com.testflow.api.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/environments")
public class EnvironmentController {
  private final EnvironmentService service;

  public EnvironmentController(EnvironmentService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<?> list(
      @PathVariable long projectId,
      @RequestParam(required = false) String name,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.of(service.list(projectId, name, status, page, size));
  }

  @GetMapping("/{id}")
  public ApiResponse<?> get(@PathVariable long projectId, @PathVariable long id) {
    return ApiResponse.of(service.get(projectId, id));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<?> create(
      @PathVariable long projectId, @Valid @RequestBody EnvironmentDtos.Create input) {
    return ApiResponse.of(service.create(projectId, input));
  }

  @PatchMapping("/{id}")
  public ApiResponse<?> update(
      @PathVariable long projectId,
      @PathVariable long id,
      @Valid @RequestBody EnvironmentDtos.Patch input) {
    return ApiResponse.of(service.update(projectId, id, input));
  }
}
