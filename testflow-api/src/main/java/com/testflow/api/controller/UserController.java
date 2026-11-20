package com.testflow.api.controller;

import com.testflow.api.dto.UserDtos;
import com.testflow.api.service.AccountService;
import com.testflow.api.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users")
public class UserController {
  private final AccountService service;

  public UserController(AccountService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<?> list(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.of(service.list(page, size));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<?> create(@Valid @RequestBody UserDtos.Create input) {
    return ApiResponse.of(service.create(input));
  }

  @GetMapping("/{id}/disable-impact")
  public ApiResponse<?> disableImpact(@PathVariable long id) {
    return ApiResponse.of(service.disableImpact(id));
  }

  @PatchMapping("/{id}")
  public ApiResponse<?> update(@PathVariable long id, @Valid @RequestBody UserDtos.Patch input) {
    return ApiResponse.of(service.update(id, input));
  }
}
