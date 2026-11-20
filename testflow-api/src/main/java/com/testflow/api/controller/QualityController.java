package com.testflow.api.controller;

import com.testflow.api.quality.QualityService;
import com.testflow.api.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class QualityController {
  private final QualityService service;

  public QualityController(QualityService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/projects/{projectId}/quality")
  public ApiResponse<?> view(
      @PathVariable long projectId,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) String sourceId,
      @RequestParam(required = false) String environmentId,
      @RequestParam(required = false) String finishedFrom,
      @RequestParam(required = false) String finishedTo,
      @RequestParam(defaultValue = "false") boolean includeIncomplete) {
    return ApiResponse.of(
        service.view(
            projectId,
            sourceType,
            sourceId,
            environmentId,
            finishedFrom,
            finishedTo,
            includeIncomplete));
  }

  @GetMapping("/api/v1/projects/{projectId}/quality/failures")
  public ApiResponse<?> failures(
      @PathVariable long projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.of(service.failures(projectId, page, size));
  }

  public record Batch(@Min(1) @Max(100) Integer limit) {}

  @PostMapping("/api/v1/projects/{projectId}/quality/reconcile")
  public ApiResponse<?> reconcile(@PathVariable long projectId, @Valid @RequestBody Batch batch) {
    return ApiResponse.of(service.reconcile(projectId, batch.limit() == null ? 50 : batch.limit()));
  }

  @PostMapping("/api/v1/projects/{projectId}/quality/failures/{id}/replay")
  public ApiResponse<?> replay(
      @PathVariable long projectId,
      @PathVariable long id,
      @RequestBody java.util.Map<String, Object> body) {
    if (!body.isEmpty()) throw com.testflow.api.web.ApiException.invalid("重放不接受载荷覆盖");
    return ApiResponse.of(service.replay(projectId, id));
  }

  @GetMapping("/api/v1/admin/quality/failures/unassigned")
  public ApiResponse<?> unassigned() {
    return ApiResponse.of(service.unassigned());
  }
}
