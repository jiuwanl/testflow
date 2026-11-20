package com.testflow.api.web;

import org.slf4j.MDC;

public record ApiResponse<T>(T data, String requestId) {
  public static <T> ApiResponse<T> of(T data) {
    return new ApiResponse<>(data, MDC.get("requestId"));
  }
}
