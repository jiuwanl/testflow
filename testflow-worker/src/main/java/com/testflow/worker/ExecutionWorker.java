package com.testflow.worker;

import com.testflow.core.execution.*;
import com.testflow.persistence.RunStore;
import com.testflow.persistence.entity.RunEntity;
import com.testflow.persistence.mapper.RunMapper;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExecutionWorker {
  private static final Logger LOG = LoggerFactory.getLogger(ExecutionWorker.class);

  private record Active(
      RunLease lease,
      ExecutionControl control,
      ConcurrentMap<Integer, ExecutionControl> requests,
      java.util.Set<CompletableFuture<StepResult>> jobs) {
    Active(RunLease lease) {
      this(lease, new ExecutionControl(), new ConcurrentHashMap<>(), ConcurrentHashMap.newKeySet());
      control.onAbort(() -> requests.values().forEach(request -> request.cancel(control.reason())));
    }
  }

  private record StepResult(ExecutionResult result, boolean persisted) {}

  private final BoundedStepPool steps;

  private final ConcurrentMap<Long, Active> active = new ConcurrentHashMap<>();
  private final AtomicBoolean stopping = new AtomicBoolean();
  private final WorkerHeartbeat node;
  private final RunStore store;
  private final RunMapper runs;
  private final SnapshotCodec codec;
  private final SafeHttpExecutor engine;
  private final int maxRuns;
  private final long leaseMs;
  private final ThreadPoolExecutor workers;

  public ExecutionWorker(
      BoundedStepPool steps,
      WorkerHeartbeat node,
      RunStore store,
      RunMapper runs,
      SnapshotCodec codec,
      SafeHttpExecutor engine,
      @Value("${TF_RUN_CONCURRENCY:2}") int maxRuns,
      @Value("${TF_LEASE_MS:30000}") long leaseMs,
      @Value("${TF_HEARTBEAT_MS:5000}") long heartbeatMs) {
    if (maxRuns < 1
        || maxRuns > 2
        || heartbeatMs < 100
        || leaseMs < Math.max(3000, heartbeatMs * 3)
        || leaseMs > 120000)
      throw new IllegalArgumentException("Invalid Worker concurrency or lease limits");
    this.steps = steps;
    this.node = node;
    this.store = store;
    this.runs = runs;
    this.codec = codec;
    this.engine = engine;
    this.maxRuns = maxRuns;
    this.leaseMs = leaseMs;
    workers =
        new ThreadPoolExecutor(
            maxRuns,
            maxRuns,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(maxRuns),
            Thread.ofPlatform().name("testflow-execution-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  @Scheduled(fixedDelayString = "${TF_POLL_INTERVAL_MS:1000}", initialDelay = 1000)
  public synchronized void poll() {
    if (stopping.get() || !node.ready()) return;
    try {
      while (active.size() < maxRuns && !stopping.get()) {
        RunEntity run = store.claim(node.workerId(), leaseMs);
        if (run == null) break;
        Active task = new Active(RunStore.lease(run));
        active.put(run.getId(), task);
        try {
          workers.execute(() -> execute(run, task));
        } catch (RejectedExecutionException ex) {
          active.remove(run.getId());
          store.interrupt(task.lease(), "WORKER_SHUTDOWN");
          break;
        }
      }
    } catch (RuntimeException ex) {
      LOG.warn("Worker polling unavailable type={}", ex.getClass().getSimpleName());
    }
  }

  @Scheduled(fixedDelayString = "${TF_HEARTBEAT_MS:5000}", initialDelay = 1000)
  public void pulse() {
    for (Active task : active.values()) {
      try {
        if (!node.ready()) {
          task.control().cancel(ExecutionControl.Reason.LEASE_LOST);
          continue;
        }
        signal(task, store.pulse(task.lease(), leaseMs));
      } catch (RuntimeException ex) {
        task.control().cancel(ExecutionControl.Reason.LEASE_LOST);
        LOG.warn(
            "Run heartbeat unavailable run={} type={}",
            task.lease().runId(),
            ex.getClass().getSimpleName());
      }
    }
  }

  private void signal(Active task, RunStore.Signal signal) {
    if (signal == RunStore.Signal.CANCELLING)
      task.control().cancel(ExecutionControl.Reason.USER_CANCELLED);
    else if (signal == RunStore.Signal.LOST)
      task.control().cancel(ExecutionControl.Reason.LEASE_LOST);
  }

  private boolean permitted(Active task, ExecutionSnapshot snapshot) {
    try {
      if (stopping.get()) {
        task.control().cancel(ExecutionControl.Reason.WORKER_SHUTDOWN);
        return false;
      }
      if (store.permitted(task.lease(), snapshot)) return true;
      signal(task, store.pulse(task.lease(), leaseMs));
      return false;
    } catch (RuntimeException ex) {
      task.control().cancel(ExecutionControl.Reason.LEASE_LOST);
      return false;
    }
  }

  private void execute(RunEntity run, Active task) {
    try {
      var stored = runs.snapshotFor(run.getId());
      ExecutionSnapshot snapshot;
      try {
        snapshot = codec.decode(stored.encryptedPayload(), stored.contentHmac());
        if (snapshot.runId() != run.getId()
            || snapshot.environmentId() != run.getEnvId()
            || snapshot.projectId() != run.getProjectId()
            || snapshot.requestedBy() != run.getRequestedBy()
            || snapshot.steps().size() != run.getTotalCount()
            || !snapshot.executionMode().equals(run.getExecutionMode())
            || snapshot.maxConcurrency() != run.getMaxConcurrency()
            || ("CASE".equals(run.getKind())
                && snapshot.steps().getFirst().caseId() != run.getSourceId()))
          throw new IllegalStateException();
      } catch (RuntimeException ex) {
        persist(task, ExecutionResult.error("SNAPSHOT_INVALID", 0), null);
        return;
      }
      if ("INDEPENDENT".equals(snapshot.executionMode())) independent(task, snapshot);
      else ordered(task, snapshot);
    } catch (RuntimeException ex) {
      // Never execute HTTP a second time to obtain a lost result.
      LOG.warn("Run execution stopped run={} type={}", run.getId(), ex.getClass().getSimpleName());
      try {
        store.interrupt(task.lease(), "WORKER_EXECUTION_STOPPED");
      } catch (RuntimeException ignored) {
        /* API recovery owns eventual finalization. */
      }
    } finally {
      // Keep the run slot until all actual jobs have finished; Future cancellation releases none.
      task.control().cancel(ExecutionControl.Reason.WORKER_SHUTDOWN);
      CompletableFuture.allOf(task.jobs().toArray(CompletableFuture[]::new))
          .whenComplete((value, error) -> active.remove(run.getId(), task));
    }
  }

  private CompletableFuture<StepResult> submitStep(
      Active task,
      ExecutionSnapshot snapshot,
      int index,
      java.util.Map<String, tools.jackson.databind.JsonNode> variables,
      java.util.Set<String> secrets) {
    var job =
        steps.trySubmit(
            () -> {
              if (task.control().reason() != null || !store.begin(task.lease(), index + 1))
                return new StepResult(null, false);
              var control = new ExecutionControl();
              task.requests().put(index, control);
              if (task.control().reason() != null) control.cancel(task.control().reason());
              try {
                var result =
                    engine.execute(
                        snapshot,
                        index,
                        variables,
                        secrets,
                        control,
                        () -> permitted(task, snapshot));
                String interruption =
                    java.util.Set.of("AUTHORIZATION_REVOKED", "LEASE_LOST", "WORKER_SHUTDOWN")
                            .contains(result.errorCode() == null ? "" : result.errorCode())
                        ? result.errorCode()
                        : null;
                if (interruption != null)
                  task.control()
                      .cancel(
                          "WORKER_SHUTDOWN".equals(interruption)
                              ? ExecutionControl.Reason.WORKER_SHUTDOWN
                              : ExecutionControl.Reason.LEASE_LOST);
                return new StepResult(
                    result, persist(task, index + 1, result, interruption, snapshot.failFast()));
              } finally {
                task.requests().remove(index, control);
              }
            });
    if (job != null) {
      task.jobs().add(job);
      job.whenComplete((value, error) -> task.jobs().remove(job));
    }
    return job;
  }

  private boolean mayDispatch(Active task) {
    if (stopping.get() || steps.closed() || task.control().reason() != null) return false;
    var run = runs.find(task.lease().runId());
    if (run == null) return false;
    if ("CANCELLING".equals(run.getState()))
      task.control().cancel(ExecutionControl.Reason.USER_CANCELLED);
    else if (!run.isLeaseValid()
        || !java.util.Objects.equals(run.getOwnerId(), task.lease().ownerId())
        || run.getLeaseVersion() != task.lease().version())
      task.control().cancel(ExecutionControl.Reason.LEASE_LOST);
    else if (run.isDeadlineExpired()) task.control().cancel(ExecutionControl.Reason.RUN_DEADLINE);
    return task.control().reason() == null
        && "RUNNING".equals(run.getState())
        && run.isLeaseValid()
        && !run.isDeadlineExpired()
        && !"FAIL_FAST".equals(run.getTerminalReason());
  }

  private void pause() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Worker coordinator interrupted");
    }
  }

  private StepResult await(CompletableFuture<StepResult> future) {
    try {
      return future.get();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Worker coordinator interrupted");
    } catch (ExecutionException ex) {
      throw new IllegalStateException("Worker step stopped");
    }
  }

  private void ordered(Active task, ExecutionSnapshot snapshot) {
    java.util.Map<String, tools.jackson.databind.JsonNode> variables = new java.util.HashMap<>();
    java.util.Set<String> secrets = new java.util.HashSet<>();
    for (int index = 0; index < snapshot.steps().size(); index++) {
      CompletableFuture<StepResult> future = null;
      while (future == null) {
        if (!mayDispatch(task)) return;
        future = submitStep(task, snapshot, index, variables, secrets);
        if (future == null) pause();
      }
      var finished = await(future);
      if (!finished.persisted()) return;
      var result = finished.result();
      if ("PASSED".equals(result.state())) {
        variables.putAll(result.outputs());
        secrets.addAll(result.outputSecrets());
      } else
        for (var extractor : snapshot.steps().get(index).config().path("extractors"))
          variables.remove(extractor.path("name").asString());
      if (snapshot.failFast() && !"PASSED".equals(result.state())) return;
    }
  }

  private void independent(Active task, ExecutionSnapshot snapshot) {
    var pending = new java.util.ArrayList<CompletableFuture<StepResult>>();
    int next = 0;
    while (next < snapshot.steps().size() || !pending.isEmpty()) {
      for (var iterator = pending.iterator(); iterator.hasNext(); ) {
        var future = iterator.next();
        if (future.isDone()) {
          await(future);
          iterator.remove();
        }
      }
      if (!mayDispatch(task)) {
        for (var future : pending) await(future);
        return;
      }
      while (next < snapshot.steps().size() && pending.size() < snapshot.maxConcurrency()) {
        if (!mayDispatch(task)) break;
        var future = submitStep(task, snapshot, next, java.util.Map.of(), java.util.Set.of());
        if (future == null) break;
        pending.add(future);
        next++;
      }
      if (next >= snapshot.steps().size() && pending.isEmpty()) return;
      pause();
    }
  }

  private void persist(Active task, ExecutionResult result, String interruption) {
    persist(task, 1, result, interruption, true);
  }

  private boolean persist(
      Active task, int ordinal, ExecutionResult result, String interruption, boolean failFast) {
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return store.finish(task.lease(), ordinal, result, interruption, failFast);
      } catch (RuntimeException ex) {
        if (attempt == 2) throw ex;
        try {
          Thread.sleep(150L * (attempt + 1));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    return false;
  }

  @PreDestroy
  public void close() {
    stopping.set(true);
    active.values().forEach(task -> task.control().cancel(ExecutionControl.Reason.WORKER_SHUTDOWN));
    workers.shutdownNow();
    for (Active task : active.values()) {
      try {
        store.interrupt(task.lease(), "WORKER_SHUTDOWN");
      } catch (RuntimeException ignored) {
        /* Lease expiration remains authoritative if DB is unavailable. */
      }
    }
  }
}
