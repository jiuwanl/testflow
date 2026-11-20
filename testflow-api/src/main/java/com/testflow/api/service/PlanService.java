package com.testflow.api.service;

import com.testflow.api.dto.PlanDtos;
import com.testflow.api.security.CurrentAccount;
import com.testflow.api.web.*;
import com.testflow.core.ProjectRole;
import com.testflow.persistence.entity.*;
import com.testflow.persistence.mapper.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanService {
  private final tools.jackson.databind.json.JsonMapper json;
  private final TestPlanMapper mapper;
  private final PlanStepMapper steps;
  private final ApiCaseMapper cases;
  private final ProjectAccess access;
  private final CurrentAccount current;
  private final AuditMapper audit;

  public PlanService(
      tools.jackson.databind.json.JsonMapper json,
      TestPlanMapper mapper,
      PlanStepMapper steps,
      ApiCaseMapper cases,
      ProjectAccess access,
      CurrentAccount current,
      AuditMapper audit) {
    this.json = json;
    this.mapper = mapper;
    this.steps = steps;
    this.cases = cases;
    this.access = access;
    this.current = current;
    this.audit = audit;
  }

  public PageResult<PlanDtos.Summary> list(
      long projectId, String name, String status, int page, int size) {
    access.require(projectId, ProjectRole.VIEWER, false);
    int offset = PageResult.offset(page, size);
    name = ServiceSupport.filterName(name);
    status = ServiceSupport.filterStatus(status);
    return new PageResult<>(
        mapper.list(projectId, name, status, offset, size).stream()
            .map(
                e ->
                    new PlanDtos.Summary(
                        e.getId().toString(),
                        e.getName(),
                        e.getExecutionMode(),
                        e.isFailFast(),
                        e.getMaxConcurrency(),
                        e.getStatus(),
                        e.getVersion()))
            .toList(),
        page,
        size,
        mapper.count(projectId, name, status));
  }

  public PlanDtos.View get(long projectId, long id) {
    access.require(projectId, ProjectRole.VIEWER, false);
    return view(find(projectId, id));
  }

  @Transactional
  public PlanDtos.View create(long projectId, PlanDtos.Create input) {
    access.require(projectId, ProjectRole.MAINTAINER, true);
    validateSteps(projectId, input.steps());
    var e = new TestPlanEntity();
    e.setProjectId(projectId);
    e.setName(ServiceSupport.name(input.name()));
    e.setExecutionMode(input.executionMode());
    e.setFailFast(input.failFast());
    e.setMaxConcurrency(
        input.maxConcurrency() == null
            ? ("INDEPENDENT".equals(input.executionMode()) ? 2 : 1)
            : input.maxConcurrency());
    validateMode(e, input.steps());
    e.setStatus("ACTIVE");
    e.setCreatedBy(current.get().getId());
    e.setUpdatedBy(current.get().getId());
    mapper.insert(e);
    replaceSteps(projectId, e.getId(), input.steps());
    audit.insert(projectId, current.get().getId(), "PLAN_CREATE", "PLAN", e.getId());
    return view(find(projectId, e.getId()));
  }

  @Transactional
  public PlanDtos.View update(long projectId, long id, PlanDtos.Patch input) {
    access.require(projectId, ProjectRole.MAINTAINER, true);
    var e = find(projectId, id);
    if (e.getVersion() != input.version()) {
      throw ApiException.conflict();
    }
    if (input.steps() != null) {
      validateSteps(projectId, input.steps());
    }
    if (input.name() != null) {
      e.setName(ServiceSupport.name(input.name()));
    }
    if (input.executionMode() != null) {
      e.setExecutionMode(input.executionMode());
    }
    if (input.failFast() != null) {
      e.setFailFast(input.failFast());
    }
    if (input.status() != null) {
      e.setStatus(input.status());
    }
    if (input.maxConcurrency() != null) e.setMaxConcurrency(input.maxConcurrency());
    else if (input.executionMode() != null)
      e.setMaxConcurrency("INDEPENDENT".equals(input.executionMode()) ? 2 : 1);
    validateMode(
        e,
        input.steps() == null
            ? steps.list(projectId, id).stream()
                .map(s -> new PlanDtos.Step(s.getCaseId().toString(), s.isEnabled()))
                .toList()
            : input.steps());
    e.setUpdatedBy(current.get().getId());
    ServiceSupport.changed(mapper.update(e));
    if (input.steps() != null) {
      replaceSteps(projectId, id, input.steps());
    }
    audit.insert(projectId, current.get().getId(), "PLAN_UPDATE", "PLAN", id);
    return view(find(projectId, id));
  }

  private void validateMode(TestPlanEntity plan, List<PlanDtos.Step> values) {
    try {
      com.testflow.core.execution.ExecutionPolicy.validate(
          plan.getExecutionMode(), plan.getMaxConcurrency());
      if ("INDEPENDENT".equals(plan.getExecutionMode()))
        for (var step : values) {
          var entry = cases.find(plan.getProjectId(), ServiceSupport.id(step.caseId()));
          if (entry == null) throw ApiException.missing();
          com.testflow.core.execution.ExecutionPolicy.independent(
              json.readTree(entry.getConfigJson()));
        }
    } catch (IllegalArgumentException ex) {
      throw ApiException.invalid(ex.getMessage());
    }
  }

  private void validateSteps(long projectId, List<PlanDtos.Step> values) {
    for (var step : values) {
      var e = cases.find(projectId, ServiceSupport.id(step.caseId()));
      if (e == null) {
        throw ApiException.missing();
      }
      if (!"ACTIVE".equals(e.getStatus())) {
        throw ApiException.invalid("计划只能引用活动用例");
      }
    }
  }

  private void replaceSteps(long projectId, long planId, List<PlanDtos.Step> values) {
    steps.removeAll(projectId, planId);
    int ordinal = 0;
    for (var value : values) {
      var step = new PlanStepEntity();
      step.setProjectId(projectId);
      step.setPlanId(planId);
      step.setCaseId(ServiceSupport.id(value.caseId()));
      step.setOrdinal(++ordinal);
      step.setEnabled(value.enabled());
      steps.insert(step);
    }
  }

  private TestPlanEntity find(long projectId, long id) {
    var e = mapper.find(projectId, id);
    if (e == null) {
      throw ApiException.missing();
    }
    return e;
  }

  private PlanDtos.View view(TestPlanEntity e) {
    return new PlanDtos.View(
        e.getId().toString(),
        e.getName(),
        e.getExecutionMode(),
        e.isFailFast(),
        e.getMaxConcurrency(),
        steps.list(e.getProjectId(), e.getId()).stream()
            .map(s -> new PlanDtos.Step(s.getCaseId().toString(), s.isEnabled()))
            .toList(),
        e.getStatus(),
        e.getVersion());
  }
}
