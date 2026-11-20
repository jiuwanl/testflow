package com.testflow.api.controller;

import com.testflow.api.dto.RunDtos;
import com.testflow.api.service.*;
import com.testflow.api.web.ApiResponse;
import com.testflow.persistence.RunStore;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
public class RunController {
  private final RunSubmissionService submissions;
  private final RunQueryService queries;

  public RunController(RunSubmissionService submissions, RunQueryService queries) {
    this.submissions = submissions;
    this.queries = queries;
  }

  @PostMapping(consumes = "application/json")
  public ResponseEntity<?> submit(
      @PathVariable long projectId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody RunDtos.Submit input) {
    var submission = submissions.submit(projectId, key, input);
    var run = submission.run();
    String url = "/api/v1/projects/" + projectId + "/runs/" + run.getId();
    return ResponseEntity.status(submission.created() ? 202 : 200)
        .location(URI.create(url))
        .body(
            ApiResponse.of(
                new RunDtos.Accepted(
                    run.getId().toString(), run.getState(), run.getOutcome(), url)));
  }

  @PostMapping(value = "/{id}/reruns", consumes = "application/json")
  public ResponseEntity<?> rerun(
      @PathVariable long projectId,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody java.util.Map<String, Object> body) {
    if (!body.isEmpty()) throw com.testflow.api.web.ApiException.invalid("原快照重跑不接受配置覆盖");
    var submission = submissions.rerun(projectId, id, key);
    var run = submission.run();
    String url = "/api/v1/projects/" + projectId + "/runs/" + run.getId();
    return ResponseEntity.status(submission.created() ? 202 : 200)
        .location(URI.create(url))
        .body(
            ApiResponse.of(
                new RunDtos.Accepted(
                    run.getId().toString(), run.getState(), run.getOutcome(), url)));
  }

  @GetMapping
  public ApiResponse<?> list(
      @PathVariable long projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) String sourceId,
      @RequestParam(required = false) String environmentId,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String createdFrom,
      @RequestParam(required = false) String createdTo) {
    return ApiResponse.of(
        queries.list(
            projectId,
            page,
            size,
            sourceType,
            sourceId,
            environmentId,
            state,
            outcome,
            createdFrom,
            createdTo));
  }

  @GetMapping("/{id}")
  public ApiResponse<?> get(@PathVariable long projectId, @PathVariable long id) {
    return ApiResponse.of(queries.get(projectId, id));
  }

  @GetMapping("/{id}/items")
  public ApiResponse<?> items(
      @PathVariable long projectId,
      @PathVariable long id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.of(queries.items(projectId, id, page, size));
  }

  @GetMapping("/{id}/items/{itemId}")
  public ApiResponse<?> detail(
      @PathVariable long projectId, @PathVariable long id, @PathVariable long itemId) {
    return ApiResponse.of(queries.detail(projectId, id, itemId));
  }

  @GetMapping("/{id}/report")
  public ApiResponse<?> report(@PathVariable long projectId, @PathVariable long id) {
    return ApiResponse.of(queries.report(projectId, id));
  }

  @PostMapping(value = "/{id}/cancel", consumes = "application/json")
  public ResponseEntity<?> cancel(@PathVariable long projectId, @PathVariable long id) {
    var run = queries.cancel(projectId, id);
    return ResponseEntity.status(RunStore.terminal(run.state()) ? 200 : 202)
        .body(ApiResponse.of(run));
  }
}
