package com.testflow.worker;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One Worker-wide budget, retained until the actual runnable exits, even after cancellation. */
@Component
public final class BoundedStepPool implements AutoCloseable {
  private final Semaphore slots;
  private final ThreadPoolExecutor executor;

  public BoundedStepPool(@Value("${TF_HTTP_CONCURRENCY:4}") int limit) {
    if (limit < 1 || limit > 4)
      throw new IllegalArgumentException("Worker HTTP concurrency must be 1..4");
    slots = new Semaphore(limit, true);
    executor =
        new ThreadPoolExecutor(
            limit,
            limit,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(limit),
            Thread.ofPlatform().name("testflow-step-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  public <T> CompletableFuture<T> trySubmit(Callable<T> action) {
    if (executor.isShutdown() || !slots.tryAcquire()) return null;
    var future = new CompletableFuture<T>();
    try {
      executor.execute(
          () -> {
            try {
              future.complete(action.call());
            } catch (Throwable error) {
              future.completeExceptionally(error);
            } finally {
              slots.release();
            }
          });
    } catch (RejectedExecutionException rejected) {
      slots.release();
      return null;
    }
    return future;
  }

  public boolean closed() {
    return executor.isShutdown();
  }

  @PreDestroy
  @Override
  public void close() {
    executor.shutdown();
  }
}
