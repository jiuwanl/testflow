package com.testflow.api.web;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private final ApiErrorWriter errors;

  public GlobalExceptionHandler(ApiErrorWriter errors) {
    this.errors = errors;
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> business(ApiException ex) {
    var response = ResponseEntity.status(ex.status());
    if (ex.status() == 429) {
      response.header("Retry-After", "300");
    }
    return response.body(errors.body(ex.code(), ex.getMessage(), ex.details()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<?> validation(MethodArgumentNotValidException ex) {
    var fields =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                e ->
                    Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
            .toList();
    return ResponseEntity.badRequest().body(errors.body("VALIDATION_ERROR", "请检查输入字段", fields));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    HandlerMethodValidationException.class,
    IllegalArgumentException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    MissingRequestHeaderException.class
  })
  ResponseEntity<?> unreadable(Exception ex) {
    Throwable cause = ex;
    while (cause != null) {
      if (cause instanceof RequestBoundaryFilter.PayloadLimitException) {
        return ResponseEntity.status(413)
            .body(errors.body("PAYLOAD_TOO_LARGE", "请求体超过限制", List.of()));
      }
      cause = cause.getCause();
    }
    return ResponseEntity.badRequest()
        .body(errors.body("VALIDATION_ERROR", "请求格式或字段不正确", List.of()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> integrity() {
    return ResponseEntity.status(409)
        .body(errors.body("STATE_CONFLICT", "名称重复或关联约束不满足", List.of()));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> dependency() {
    return ResponseEntity.status(503)
        .body(errors.body("DEPENDENCY_UNAVAILABLE", "数据服务暂不可用", List.of()));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<?> notFound() {
    return business(ApiException.missing());
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<?> methodNotAllowed() {
    return ResponseEntity.status(405)
        .body(errors.body("METHOD_NOT_ALLOWED", "此接口不支持该请求方法", List.of()));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<?> unsupportedMedia() {
    return ResponseEntity.status(415)
        .body(errors.body("UNSUPPORTED_MEDIA_TYPE", "请求必须使用 JSON 格式", List.of()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception ex) {
    LOG.error("Unhandled request failure type={}", ex.getClass().getName());
    return ResponseEntity.internalServerError()
        .body(errors.body("INTERNAL_ERROR", "服务处理失败，请提供请求追踪标识", List.of()));
  }
}
