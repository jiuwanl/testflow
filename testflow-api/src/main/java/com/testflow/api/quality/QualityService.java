package com.testflow.api.quality;

import com.testflow.api.security.CurrentAccount;
import com.testflow.api.service.*;
import com.testflow.api.web.*;
import com.testflow.core.ProjectRole;
import com.testflow.persistence.mapper.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class QualityService {
  private final QualityMapper facts;
  private final OutboxMapper events;
  private final OutboxStore store;
  private final ProjectAccess access;
  private final CurrentAccount current;
  private final AuditMapper audit;
  private final boolean enabled;

  public QualityService(
      QualityMapper facts,
      OutboxMapper events,
      OutboxStore store,
      ProjectAccess access,
      CurrentAccount current,
      AuditMapper audit,
      @Value("${testflow.quality.enabled:false}") boolean enabled) {
    this.facts = facts;
    this.events = events;
    this.store = store;
    this.access = access;
    this.current = current;
    this.audit = audit;
    this.enabled = enabled;
  }

  public record Metrics(
      long runs,
      long total,
      long passed,
      long failed,
      long errors,
      long skipped,
      long cancelled,
      long unknown,
      Double coverage,
      Double passRate,
      Double failureRate,
      Double errorRate,
      Double averageDurationMs) {}

  public record Status(
      String state,
      boolean enabled,
      String asOf,
      String updatedAt,
      long terminalRuns,
      long projectedRuns,
      long missingRuns,
      long pendingEvents,
      long incompleteRuns,
      String oldestMissingAt) {}

  public record Day(String date, Metrics metrics) {}

  public record View(
      Status status, Metrics metrics, List<Day> days, List<Map<String, Object>> recent) {}

  static long number(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value instanceof Number n ? n.longValue() : 0;
  }

  static String text(Object value) {
    if (value == null) return null;
    if (value instanceof java.sql.Timestamp time) return time.toInstant().toString();
    if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC).toString();
    return value.toString();
  }

  static Double percent(long part, long total) {
    return total == 0 ? null : Math.round(part * 10000.0 / total) / 100.0;
  }

  static Metrics metrics(Map<String, Object> row) {
    long total = number(row, "total"),
        passed = number(row, "passed"),
        failed = number(row, "failed"),
        errors = number(row, "errors"),
        known = passed + failed + errors;
    return new Metrics(
        number(row, "runs"),
        total,
        passed,
        failed,
        errors,
        number(row, "skipped"),
        number(row, "cancelled"),
        number(row, "unknown"),
        percent(known, total),
        percent(passed, known),
        percent(failed, known),
        percent(errors, known),
        row.get("averageDurationMs") instanceof Number n ? n.doubleValue() : null);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 2)
  public View view(
      long projectId,
      String kind,
      String sourceId,
      String envId,
      String fromText,
      String toText,
      boolean includeIncomplete) {
    access.require(projectId, ProjectRole.VIEWER, false);
    if (kind != null && !Set.of("CASE", "PLAN").contains(kind))
      throw ApiException.invalid("来源类型无效");
    if (sourceId != null && kind == null) throw ApiException.invalid("来源 ID 需要来源类型");
    Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
    Instant from, to;
    try {
      from = fromText == null ? today.minus(29, ChronoUnit.DAYS) : Instant.parse(fromText);
      to = toText == null ? today.plus(1, ChronoUnit.DAYS) : Instant.parse(toText);
    } catch (RuntimeException error) {
      throw ApiException.invalid("时间须为含时区的 ISO-8601 格式");
    }
    if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(90)) > 0)
      throw ApiException.invalid("统计时间范围须为正且不超过 90 天");
    var filter =
        new QualityMapper.Filter(
            projectId,
            kind,
            sourceId == null ? null : ServiceSupport.id(sourceId),
            envId == null ? null : ServiceSupport.id(envId),
            from,
            to,
            includeIncomplete);
    var row = facts.status(filter);
    long terminal = number(row, "terminalRuns"),
        projected = number(row, "projectedRuns"),
        missing = terminal - projected;
    String state =
        !enabled ? "DISABLED" : missing > 0 ? "LAGGING" : terminal == 0 ? "EMPTY" : "CURRENT";
    return new View(
        new Status(
            state,
            enabled,
            Instant.now().toString(),
            text(row.get("latestProjectedAt")),
            terminal,
            projected,
            missing,
            number(row, "pendingEvents"),
            number(row, "incompleteRuns"),
            text(row.get("oldestMissingAt"))),
        metrics(facts.totals(filter)),
        facts.days(filter).stream()
            .map(day -> new Day(text(day.get("day")), metrics(day)))
            .toList(),
        facts.recent(filter).stream()
            .map(
                item -> {
                  var value = new LinkedHashMap<String, Object>(item);
                  value.put("finishedAt", text(item.get("finishedAt")));
                  return (Map<String, Object>) value;
                })
            .toList());
  }

  public PageResult<Map<String, Object>> failures(long projectId, int page, int size) {
    access.require(projectId, ProjectRole.VIEWER, false);
    return new PageResult<>(
        facts.failures(projectId, PageResult.offset(page, size), size).stream()
            .map(this::safeFailure)
            .toList(),
        page,
        size,
        facts.failureCount(projectId));
  }

  private Map<String, Object> safeFailure(Map<String, Object> input) {
    var result = new LinkedHashMap<String, Object>();
    input.forEach(
        (key, value) ->
            result.put(
                switch (key) {
                  case "event_id" -> "eventId";
                  case "error_code" -> "errorCode";
                  case "replay_count" -> "replayCount";
                  case "first_seen_at" -> "firstSeenAt";
                  case "last_seen_at" -> "lastSeenAt";
                  case "project_id" -> "projectId";
                  default -> key;
                },
                key.equals("id")
                        || key.equals("project_id")
                        || value instanceof java.sql.Timestamp
                        || value instanceof LocalDateTime
                    ? text(value)
                    : value));
    return result;
  }

  public List<Map<String, Object>> unassigned() {
    current.admin();
    return facts.unassignedFailures().stream().map(this::safeFailure).toList();
  }

  public record Reconciliation(int createdEvents, int requeuedEvents, int limit) {}

  public Reconciliation reconcile(long projectId, int limit) {
    access.require(projectId, ProjectRole.MAINTAINER, false);
    if (limit < 1 || limit > 100) throw ApiException.invalid("补齐批次范围为 1～100");
    int created = store.backfill(projectId, limit);
    int requeued = 0;
    for (String id : events.missingFacts(projectId, limit - created))
      if (requeue(projectId, id)) requeued++;
    audit.insert(projectId, current.get().getId(), "QUALITY_RECONCILE", "PROJECT", projectId);
    return new Reconciliation(created, requeued, limit);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 2)
  public Map<String, String> replay(long projectId, long failureId) {
    access.require(projectId, ProjectRole.MAINTAINER, false);
    var failure = facts.failure(projectId, failureId);
    if (failure == null) throw ApiException.missing();
    String id = text(failure.get("event_id"));
    if (id == null) throw new ApiException(409, "EVENT_UNAVAILABLE", "无可信来源事件，不能重放");
    // Failure identity is immutable. Lock the event before updating failure status,
    // matching the projection's lock order and serializing concurrent replays.
    var event = events.lock(id);
    if (event == null || event.projectId() != projectId) throw ApiException.missing();
    if (events.requeue(id) != 1) throw new ApiException(409, "EVENT_IN_FLIGHT", "事件正在投递，请稍后重试");
    facts.replayed(failureId);
    audit.insert(projectId, current.get().getId(), "QUALITY_REPLAY", "RUN", event.runId());
    return Map.of("status", "REPLAY_REQUESTED");
  }

  private boolean requeue(long projectId, String id) {
    var event = events.find(id);
    return event != null && event.projectId() == projectId && events.requeue(id) == 1;
  }
}
