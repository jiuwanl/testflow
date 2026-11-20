package com.testflow.worker;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BoundedStepPoolTest {
  @Test
  void holdsGlobalBudgetUntilActualWorkExitsEvenWhenFutureCancelled() throws Exception {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(4);
    try (var pool = new BoundedStepPool(4)) {
      var jobs = new java.util.ArrayList<CompletableFuture<Integer>>();
      for (int i = 0; i < 4; i++)
        jobs.add(
            pool.trySubmit(
                () -> {
                  entered.countDown();
                  release.await();
                  return 1;
                }));
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(pool.trySubmit(() -> 99)).isNull();
      jobs.forEach(future -> future.cancel(true));
      assertThat(pool.trySubmit(() -> 99)).isNull();
      release.countDown();
      CompletableFuture<Integer> next = null;
      for (int i = 0; i < 200 && next == null; i++) {
        next = pool.trySubmit(() -> 2);
        if (next == null) Thread.sleep(5);
      }
      assertThat(next).isNotNull();
      assertThat(next.get(3, TimeUnit.SECONDS)).isEqualTo(2);
    } finally {
      release.countDown();
    }
  }

  @Test
  void failuresReleaseSlotsAndClosedPoolRejectsWithoutRunning() throws Exception {
    var pool = new BoundedStepPool(1);
    try {
      var failed =
          pool.trySubmit(
              () -> {
                throw new IllegalStateException("synthetic");
              });
      assertThatThrownBy(() -> failed.get(3, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class);
      CompletableFuture<Integer> next = null;
      for (int i = 0; i < 200 && next == null; i++) {
        next = pool.trySubmit(() -> 7);
        if (next == null) Thread.sleep(5);
      }
      assertThat(next).isNotNull();
      assertThat(next.get(3, TimeUnit.SECONDS)).isEqualTo(7);
    } finally {
      pool.close();
    }
    assertThat(
            pool.trySubmit(
                () -> {
                  throw new AssertionError("must not run");
                }))
        .isNull();
    assertThat(pool.closed()).isTrue();
  }

  @Test
  void rejectsInvalidCapacity() {
    for (int size : new int[] {0, 5})
      assertThatThrownBy(() -> new BoundedStepPool(size))
          .isInstanceOf(IllegalArgumentException.class);
  }
}
