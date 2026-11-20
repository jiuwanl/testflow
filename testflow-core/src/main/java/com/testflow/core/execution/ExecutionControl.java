package com.testflow.core.execution;

import java.util.concurrent.atomic.AtomicReference;

/** Cooperative control plus an abort callback for the current network request. */
public final class ExecutionControl {
  public enum Reason {
    USER_CANCELLED,
    RUN_DEADLINE,
    LEASE_LOST,
    WORKER_SHUTDOWN,
    REQUEST_TIMEOUT
  }

  private final AtomicReference<Reason> reason = new AtomicReference<>();
  private final AtomicReference<Runnable> abort = new AtomicReference<>();

  public Reason reason() {
    return reason.get();
  }

  public void cancel(Reason value) {
    if (reason.compareAndSet(null, value)) {
      Runnable action = abort.get();
      if (action != null) action.run();
    }
  }

  public void onAbort(Runnable action) {
    abort.set(action);
    if (reason.get() != null) action.run();
  }

  public void clearAbort() {
    abort.set(null);
  }
}
