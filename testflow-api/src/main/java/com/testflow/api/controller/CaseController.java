package com.testflow.api.controller;

import com.testflow.api.dto.CaseDtos;
import com.testflow.api.service.CaseService;
import com.testflow.api.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/cases")
public class CaseController {
  private final CaseService service;

  public CaseController(CaseService service) {
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
      @PathVariable long projectId, @Valid @RequestBody CaseDtos.Create input) {
    return ApiResponse.of(service.create(projectId, input));
  }

  @PatchMapping("/{id}")
  public ApiResponse<?> update(
      @PathVariable long projectId,
      @PathVariable long id,
      @Valid @RequestBody CaseDtos.Patch input) {
    return ApiResponse.of(service.update(projectId, id, input));
  }
}
