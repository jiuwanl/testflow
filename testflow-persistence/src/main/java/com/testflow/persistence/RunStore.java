package com.testflow.persistence;

import com.testflow.core.execution.*;
import com.testflow.persistence.entity.*;
import com.testflow.persistence.mapper.*;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Short database transactions only. No network request or executor work occurs under a row lock.
 */
public class RunStore {
  private final RunMapper runs;
  private final TargetPolicyMapper targets;
  private final TransactionTemplate transaction;
  private final JsonMapper json;

  public RunStore(
      RunMapper runs,
      TargetPolicyMapper targets,
      PlatformTransactionManager manager,
      JsonMapper json) {
    this.runs = runs;
    this.targets = targets;
    this.json = json;
    transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(2);
  }

  public enum Signal {
    RUNNING,
    CANCELLING,
    LOST
  }

  private <T> T tx(Supplier<T> action) {
    return transaction.execute(status -> action.get());
  }

  public static boolean terminal(String state) {
    return List.of("COMPLETED", "CANCELLED", "TIMED_OUT", "INTERRUPTED").contains(state);
  }

  public static RunLease lease(RunEntity run) {
    return new RunLease(run.getId(), run.getOwnerId(), run.getLeaseVersion());
  }

  private boolean owns(RunEntity run, RunLease lease) {
    return run != null
        && Objects.equals(run.getOwnerId(), lease.ownerId())
        && run.getLeaseVersion() == lease.version()
        && run.isLeaseValid()
        && !terminal(run.getState());
  }

  public RunEntity claim(String owner, long leaseMs) {
    return tx(
        () -> {
          RunEntity run = runs.nextQueued();
          if (run == null || runs.claim(run.getId(), owner, leaseMs) != 1) return null;
          return runs.find(run.getId());
        });
  }

  public boolean begin(RunLease lease) {
    return begin(lease, 1);
  }

  public boolean begin(RunLease lease, int ordinal) {
    return tx(
        () -> {
          RunEntity run = runs.lock(lease.runId());
          if (!owns(run, lease)) return false;
          if (run.isDeadlineExpired()) {
            terminate(run, "TIMED_OUT", "RUN_DEADLINE", "SKIPPED", lease);
            return false;
          }
          if ("CANCELLING".equals(run.getState())) {
            settleCancellation(run, lease);
            return false;
          }
          if ("FAIL_FAST".equals(run.getTerminalReason())) return false;
          RunItemEntity item =
              runs.items(run.getId()).stream()
                  .filter(i -> i.getOrdinal() == ordinal)
                  .findFirst()
                  .orElseThrow();
          if ("ORDERED".equals(run.getExecutionMode())
              && runs.items(run.getId()).stream()
                  .anyMatch(
                      i ->
                          i.getOrdinal() < ordinal
                              && List.of("PENDING", "RUNNING").contains(i.getState())))
            return false;
          if (runs.items(run.getId()).stream().filter(i -> "RUNNING".equals(i.getState())).count()
              >= run.getMaxConcurrency()) return false;
          return runs.beginItem(item.getId(), lease) == 1;
        });
  }

  public Signal pulse(RunLease lease, long leaseMs) {
    return tx(
        () -> {
          RunEntity run = runs.lock(lease.runId());
          if (!owns(run, lease)) return Signal.LOST;
          if (run.isDeadlineExpired()) {
            terminate(run, "TIMED_OUT", "RUN_DEADLINE", "SKIPPED", lease);
            return Signal.LOST;
          }
          if (runs.renew(run.getId(), lease.ownerId(), lease.version(), leaseMs) != 1)
            return Signal.LOST;
          return "CANCELLING".equals(run.getState()) ? Signal.CANCELLING : Signal.RUNNING;
        });
  }

  /** Read immediately before dispatch and again by the connection's DNS resolver. */
  public boolean permitted(RunLease lease, ExecutionSnapshot snapshot) {
    if (runs.dispatchAllowed(lease.runId(), lease.ownerId(), lease.version()) != 1
        || runs.authorized(snapshot.projectId(), snapshot.requestedBy()) != 1) return false;
    for (var frozen : snapshot.approvedTargets()) {
      TargetPolicyEntity live = targets.find(snapshot.projectId(), frozen.id());
      if (live == null
          || !live.isEnabled()
          || !live.getScheme().equals(frozen.scheme())
          || !live.getHost().equals(frozen.host())
          || live.getPort() != frozen.port()
          || !live.getAddressScope().equals(frozen.addressScope())) return false;
    }
    return !snapshot.approvedTargets().isEmpty();
  }

  public boolean finish(RunLease lease, ExecutionResult result, String interruption) {
    return finish(lease, 1, result, interruption, true);
  }

  public boolean finish(
      RunLease lease, int ordinal, ExecutionResult result, String interruption, boolean failFast) {
    return tx(
        () -> {
          RunEntity run = runs.lock(lease.runId());
          if (!owns(run, lease)) return false;
          if (run.isDeadlineExpired()) {
            terminate(run, "TIMED_OUT", "RUN_DEADLINE", "SKIPPED", lease);
            return false;
          }
          RunItemEntity item =
              runs.items(run.getId()).stream()
                  .filter(i -> i.getOrdinal() == ordinal)
                  .findFirst()
                  .orElseThrow();
          if (!List.of("PENDING", "RUNNING").contains(item.getState())) return true;
          if ("PENDING".equals(item.getState())) {
            if ("CANCELLING".equals(run.getState())) {
              settleCancellation(run, lease);
              return true;
            }
            if (runs.beginItem(item.getId(), lease) != 1) return false;
          }
          if (runs.finishItem(
                  item.getId(), result.state(), result.errorCode(), result.durationMs(), lease)
              != 1) throw new IllegalStateException("Result write lost its lease");
          if (runs.result(
                  item.getId(),
                  run.getProjectId(),
                  json.writeValueAsString(result.report()),
                  result.bodyAvailable(),
                  result.bodyTruncated(),
                  lease)
              != 1) throw new IllegalStateException("Result write lost its lease");
          if (interruption != null) {
            terminate(run, "INTERRUPTED", interruption, "SKIPPED", lease);
          } else if ("CANCELLING".equals(run.getState())) {
            settleCancellation(run, lease);
          } else {
            if (failFast && !"PASSED".equals(result.state())) {
              run.setTerminalReason("FAIL_FAST");
              runs.stopPending(run.getId(), "SKIPPED", "FAIL_FAST");
            }
            if (runs.items(run.getId()).stream()
                .noneMatch(i -> List.of("PENDING", "RUNNING").contains(i.getState())))
              finalizeRun(
                  run,
                  "COMPLETED",
                  run.getTerminalReason() == null ? result.errorCode() : run.getTerminalReason(),
                  lease);
            else progress(run, lease);
          }
          return true;
        });
  }

  public RunEntity cancel(long id) {
    return tx(
        () -> {
          RunEntity run = runs.lock(id);
          if (run == null || terminal(run.getState())) return run;
          if (!"QUEUED".equals(run.getState()) && !run.isLeaseValid())
            terminate(run, "INTERRUPTED", "LEASE_EXPIRED", "SKIPPED");
          else if (run.isDeadlineExpired()) terminate(run, "TIMED_OUT", "RUN_DEADLINE", "SKIPPED");
          else if ("QUEUED".equals(run.getState()))
            terminate(run, "CANCELLED", "USER_CANCELLED", "CANCELLED");
          else {
            runs.requestCancel(id);
            run.setState("CANCELLING");
            settleCancellation(run, lease(run));
          }
          return runs.find(id);
        });
  }

  private void progress(RunEntity run, RunLease lease) {
    populateCounts(run);
    if (runs.progress(run, lease) != 1)
      throw new IllegalStateException("Progress write lost its lease");
  }

  private void settleCancellation(RunEntity run, RunLease lease) {
    runs.stopPending(run.getId(), "CANCELLED", "USER_CANCELLED");
    if (runs.items(run.getId()).stream().noneMatch(i -> "RUNNING".equals(i.getState())))
      finalizeRun(run, "CANCELLED", "USER_CANCELLED", lease);
    else progress(run, lease);
  }

  public void interrupt(RunLease lease, String reason) {
    tx(
        () -> {
          RunEntity run = runs.lock(lease.runId());
          if (owns(run, lease)) {
            if (run.isDeadlineExpired())
              terminate(run, "TIMED_OUT", "RUN_DEADLINE", "SKIPPED", lease);
            else terminate(run, "INTERRUPTED", reason, "SKIPPED", lease);
          }
          return null;
        });
  }

  /** API owns recovery. Expired ownership is handled before the overall deadline. */
  public int recover(int limit) {
    int recovered = 0;
    for (int i = 0; i < limit; i++) {
      boolean changed =
          tx(
              () -> {
                RunEntity run = runs.expiredLease();
                if (run != null) {
                  terminate(run, "INTERRUPTED", "LEASE_EXPIRED", "SKIPPED");
                  return true;
                }
                run = runs.expiredDeadline();
                if (run != null) {
                  terminate(run, "TIMED_OUT", "RUN_DEADLINE", "SKIPPED");
                  return true;
                }
                return false;
              });
      if (!changed) break;
      recovered++;
    }
    return recovered;
  }

  private void terminate(RunEntity run, String state, String reason, String pendingState) {
    terminate(run, state, reason, pendingState, null);
  }

  private void terminate(
      RunEntity run, String state, String reason, String pendingState, RunLease lease) {
    runs.terminateItems(run.getId(), pendingState, reason);
    finalizeRun(run, state, reason, lease);
  }

  private void finalizeRun(RunEntity run, String state, String reason, RunLease lease) {
    RunMetrics metrics = populateCounts(run);
    run.setState(state);
    run.setOutcome(metrics.outcome(state));
    run.setTerminalReason(reason);
    if (runs.finalizeRun(run, lease) != 1)
      throw new IllegalStateException("Run finalization no longer owns a valid lease");
    if (runs.enqueueFinalized(run.getId(), java.util.UUID.randomUUID().toString()) != 1)
      throw new IllegalStateException("Finalized event was not persisted");
  }

  private RunMetrics populateCounts(RunEntity run) {
    var metrics =
        RunMetrics.of(runs.items(run.getId()).stream().map(RunItemEntity::getState).toList());
    run.setPassedCount(metrics.passed());
    run.setFailedCount(metrics.failed());
    run.setErrorCount(metrics.errors());
    run.setSkippedCount(metrics.skipped());
    run.setCancelledCount(metrics.cancelled());
    run.setUnknownCount(metrics.unknown());
    return metrics;
  }
}
