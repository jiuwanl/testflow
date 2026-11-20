package com.testflow.core.execution;

/** Stable diagnostic code only. Never propagate third-party exception messages to reports. */
public final class ExecutionFailure extends RuntimeException {
  private final String code;

  public ExecutionFailure(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
