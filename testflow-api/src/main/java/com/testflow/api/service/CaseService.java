package com.testflow.api.service;

import com.testflow.api.dto.CaseDtos;
import com.testflow.api.security.CurrentAccount;
import com.testflow.api.web.*;
import com.testflow.core.*;
import com.testflow.persistence.entity.ApiCaseEntity;
import com.testflow.persistence.mapper.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CaseService {
  private final ApiCaseMapper mapper;
  private final ProjectAccess access;
  private final CurrentAccount current;
  private final AuditMapper audit;
  private final JsonMapper json;

  public CaseService(
      ApiCaseMapper mapper,
      ProjectAccess access,
      CurrentAccount current,
      AuditMapper audit,
      JsonMapper json) {
    this.mapper = mapper;
    this.access = access;
    this.current = current;
    this.audit = audit;
    this.json = json;
  }

  public PageResult<CaseDtos.Summary> list(
      long projectId, String name, String status, int page, int size) {
    access.require(projectId, ProjectRole.VIEWER, false);
    int offset = PageResult.offset(page, size);
    name = ServiceSupport.filterName(name);
    status = ServiceSupport.filterStatus(status);
    return new PageResult<>(
        mapper.list(projectId, name, status, offset, size).stream()
            .map(CaseDtos.Summary::from)
            .toList(),
        page,
        size,
        mapper.count(projectId, name, status));
  }

  public CaseDtos.View get(long projectId, long id) {
    access.require(projectId, ProjectRole.VIEWER, false);
    return view(find(projectId, id));
  }

  @Transactional
  public CaseDtos.View create(long projectId, CaseDtos.Create input) {
    access.require(projectId, ProjectRole.MAINTAINER, true);
    validate(input.config());
    var e = new ApiCaseEntity();
    e.setProjectId(projectId);
    e.setName(ServiceSupport.name(input.name()));
    e.setTagsJson(json.writeValueAsString(input.tags() == null ? List.of() : input.tags()));
    e.setPriority(input.priority() == null ? "P2" : input.priority());
    e.setConfigSchemaVersion(input.configSchemaVersion());
    e.setConfigJson(json.writeValueAsString(input.config()));
    e.setStatus("ACTIVE");
    e.setCreatedBy(current.get().getId());
    e.setUpdatedBy(current.get().getId());
    mapper.insert(e);
    audit.insert(projectId, current.get().getId(), "CASE_CREATE", "CASE", e.getId());
    return view(find(projectId, e.getId()));
  }

  @Transactional
  public CaseDtos.View update(long projectId, long id, CaseDtos.Patch input) {
    access.require(projectId, ProjectRole.MAINTAINER, true);
    var e = find(projectId, id);
    if (e.getVersion() != input.version()) {
      throw ApiException.conflict();
    }
    if (input.name() != null) {
      e.setName(ServiceSupport.name(input.name()));
    }
    if (input.tags() != null) {
      e.setTagsJson(json.writeValueAsString(input.tags()));
    }
    if (input.priority() != null) {
      e.setPriority(input.priority());
    }
    if (input.config() != null) {
      validate(input.config());
      e.setConfigJson(json.writeValueAsString(input.config()));
    }
    if (input.status() != null) {
      e.setStatus(input.status());
    }
    e.setUpdatedBy(current.get().getId());
    ServiceSupport.changed(mapper.update(e));
    audit.insert(projectId, current.get().getId(), "CASE_UPDATE", "CASE", e.getId());
    return view(find(projectId, id));
  }

  private ApiCaseEntity find(long projectId, long id) {
    var e = mapper.find(projectId, id);
    if (e == null) {
      throw ApiException.missing();
    }
    return e;
  }

  private void validate(JsonNode config) {
    try {
      DefinitionValidator.caseConfig(config);
    } catch (IllegalArgumentException ex) {
      throw ApiException.invalid(ex.getMessage());
    }
  }

  private CaseDtos.View view(ApiCaseEntity e) {
    return new CaseDtos.View(
        e.getId().toString(),
        e.getName(),
        List.of(json.readValue(e.getTagsJson(), String[].class)),
        e.getPriority(),
        e.getConfigSchemaVersion(),
        json.readTree(e.getConfigJson()),
        e.getStatus(),
        e.getVersion());
  }
}
