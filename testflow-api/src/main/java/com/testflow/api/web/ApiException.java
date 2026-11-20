package com.testflow.api.web;

public final class ApiException extends RuntimeException {
  private final int status;
  private java.util.List<?> details = java.util.List.of();

  public ApiException details(java.util.List<?> value) {
    this.details = value;
    return this;
  }

  public java.util.List<?> details() {
    return details;
  }

  private final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException missing() {
    return new ApiException(404, "RESOURCE_NOT_FOUND", "资源不存在或不可见");
  }

  public static ApiException conflict() {
    return new ApiException(409, "VERSION_CONFLICT", "数据已更新，请刷新后重试");
  }

  public static ApiException invalid(String message) {
    return new ApiException(400, "VALIDATION_ERROR", message);
  }
}
