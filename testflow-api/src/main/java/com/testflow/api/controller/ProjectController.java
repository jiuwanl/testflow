package com.testflow.api.controller;

import com.testflow.api.dto.ProjectDtos;
import com.testflow.api.service.ProjectService;
import com.testflow.api.web.ApiResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
  private final ProjectService service;

  public ProjectController(ProjectService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<?> list(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.of(service.list(page, size));
  }

  @GetMapping("/{id}")
  public ApiResponse<?> get(@PathVariable long id) {
    return ApiResponse.of(service.get(id));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<?> create(@Valid @RequestBody ProjectDtos.Create input) {
    return ApiResponse.of(service.create(input));
  }

  @PatchMapping("/{id}")
  public ApiResponse<?> update(@PathVariable long id, @Valid @RequestBody ProjectDtos.Patch input) {
    return ApiResponse.of(service.update(id, input));
  }

  @GetMapping("/{id}/members")
  public ApiResponse<?> members(
      @PathVariable long id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.of(service.members(id, page, size));
  }

  @GetMapping("/{id}/members/{userId}")
  public ApiResponse<?> member(@PathVariable long id, @PathVariable long userId) {
    return ApiResponse.of(service.member(id, userId));
  }

  @PutMapping("/{id}/members/{userId}")
  public ApiResponse<?> changeMember(
      @PathVariable long id,
      @PathVariable long userId,
      @Valid @RequestBody ProjectDtos.MemberChange input) {
    return ApiResponse.of(service.changeMember(id, userId, input));
  }

  @DeleteMapping("/{id}/members/{userId}")
  public ApiResponse<?> removeMember(@PathVariable long id, @PathVariable long userId) {
    service.removeMember(id, userId);
    return ApiResponse.of(Map.of("removed", true));
  }
}
