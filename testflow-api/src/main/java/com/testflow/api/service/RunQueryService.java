package com.testflow.api.service;

import com.testflow.api.dto.RunDtos;
import com.testflow.api.security.CurrentAccount;
import com.testflow.api.web.*;
import com.testflow.core.ProjectRole;
import com.testflow.persistence.RunStore;
import com.testflow.persistence.entity.RunEntity;
import com.testflow.persistence.mapper.AuditMapper;
import com.testflow.persistence.mapper.RunMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RunQueryService {
  private final RunMapper runs;
  private final ProjectAccess access;
  private final RunStore store;
  private final JsonMapper json;
  private final AuditMapper audit;
  private final CurrentAccount current;

  public RunQueryService(
      RunMapper runs,
      ProjectAccess access,
      RunStore store,
      JsonMapper json,
      AuditMapper audit,
      CurrentAccount current) {
    this.runs = runs;
    this.access = access;
    this.store = store;
    this.json = json;
    this.audit = audit;
    this.current = current;
  }

  private RunEntity require(long projectId, long id) {
    access.require(projectId, ProjectRole.VIEWER, false);
    RunEntity run = runs.find(id);
    if (run == null || run.getProjectId() != projectId) throw ApiException.missing();
    return run;
  }

  public RunDtos.View get(long projectId, long id) {
    return RunDtos.View.of(require(projectId, id));
  }

  public PageResult<RunDtos.View> list(
      long projectId,
      int page,
      int size,
      String sourceType,
      String sourceId,
      String environmentId,
      String state,
      String outcome,
      String createdFrom,
      String createdTo) {
    access.require(projectId, ProjectRole.VIEWER, false);
    int offset = PageResult.offset(page, size);
    if (sourceType != null && !java.util.Set.of("CASE", "PLAN").contains(sourceType))
      throw ApiException.invalid("来源类型无效");
    if (sourceId != null && sourceType == null) throw ApiException.invalid("筛选来源 ID 时必须指定类型");
    if (state != null
        && !java.util.Set.of(
                "QUEUED",
                "RUNNING",
                "CANCELLING",
                "COMPLETED",
                "CANCELLED",
                "TIMED_OUT",
                "INTERRUPTED")
            .contains(state)) throw ApiException.invalid("运行状态无效");
    if (outcome != null && !java.util.Set.of("PASSED", "FAILED", "INCOMPLETE").contains(outcome))
      throw ApiException.invalid("运行结论无效");
    java.time.Instant from = parseTime(createdFrom), to = parseTime(createdTo);
    if (from != null && to != null && !from.isBefore(to)) throw ApiException.invalid("时间范围须开始早于结束");
    var filter =
        new com.testflow.persistence.RunFilter(
            sourceType,
            sourceId == null ? null : ServiceSupport.id(sourceId),
            environmentId == null ? null : ServiceSupport.id(environmentId),
            state,
            outcome,
            from,
            to);
    return new PageResult<>(
        runs.list(projectId, filter, offset, size).stream().map(RunDtos.View::of).toList(),
        page,
        size,
        runs.count(projectId, filter));
  }

  private java.time.Instant parseTime(String value) {
    if (value == null) return null;
    try {
      return java.time.Instant.parse(value);
    } catch (java.time.format.DateTimeParseException ex) {
      throw ApiException.invalid("时间须使用含时区的 ISO-8601 格式");
    }
  }

  public PageResult<RunDtos.Item> items(long projectId, long id, int page, int size) {
    require(projectId, id);
    int offset = PageResult.offset(page, size);
    var all = runs.items(id);
    return new PageResult<>(
        all.stream().skip(offset).limit(size).map(RunDtos.Item::of).toList(),
        page,
        size,
        all.size());
  }

  public RunDtos.Detail detail(long projectId, long id, long itemId) {
    require(projectId, id);
    var item = runs.itemDetail(id, itemId);
    if (item == null) throw ApiException.missing();
    return new RunDtos.Detail(
        RunDtos.Item.of(item),
        item.getReportJson() == null ? null : json.readTree(item.getReportJson()),
        item.isBodyAvailable(),
        item.isBodyTruncated());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 2)
  public RunDtos.Report report(long projectId, long id) {
    var run = require(projectId, id);
    return new RunDtos.Report(
        RunDtos.View.of(run), runs.items(id).stream().map(RunDtos.Item::of).toList());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 2)
  public RunDtos.View cancel(long projectId, long id) {
    access.require(projectId, ProjectRole.MAINTAINER, false);
    var original = require(projectId, id);
    var cancelled = store.cancel(id);
    if (!RunStore.terminal(original.getState()) && !"CANCELLING".equals(original.getState())) {
      audit.insert(projectId, current.get().getId(), "RUN_CANCEL", "RUN", id);
    }
    return RunDtos.View.of(cancelled);
  }
}
