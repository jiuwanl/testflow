package com.testflow.api.service;

import com.testflow.api.dto.TargetPolicyDtos;
import com.testflow.api.security.CurrentAccount;
import com.testflow.api.web.*;
import com.testflow.core.*;
import com.testflow.persistence.entity.TargetPolicyEntity;
import com.testflow.persistence.mapper.*;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TargetPolicyService {
  private final TargetPolicyMapper mapper;
  private final ProjectMapper projects;
  private final ProjectAccess access;
  private final CurrentAccount current;
  private final AuditMapper audit;

  public TargetPolicyService(
      TargetPolicyMapper mapper,
      ProjectMapper projects,
      ProjectAccess access,
      CurrentAccount current,
      AuditMapper audit) {
    this.mapper = mapper;
    this.projects = projects;
    this.access = access;
    this.current = current;
    this.audit = audit;
  }

  public PageResult<TargetPolicyDtos.View> list(long projectId, int page, int size) {
    if (!current.get().isGlobalAdmin()) {
      access.require(projectId, ProjectRole.VIEWER, false);
    }
    if (projects.findById(projectId) == null) {
      throw ApiException.missing();
    }
    int offset = PageResult.offset(page, size);
    return new PageResult<>(
        mapper.list(projectId, offset, size).stream().map(this::view).toList(),
        page,
        size,
        mapper.count(projectId));
  }

  @Transactional
  public TargetPolicyDtos.View create(long projectId, TargetPolicyDtos.Create input) {
    var actor = current.admin();
    if (projects.findById(projectId) == null) {
      throw ApiException.missing();
    }
    String host;
    try {
      host = DefinitionValidator.host(input.host());
    } catch (IllegalArgumentException ex) {
      throw ApiException.invalid(ex.getMessage());
    }
    if (Set.of(
                "169.254.169.254",
                "metadata.google.internal",
                "fd00:ec2::254",
                "mysql",
                "redis",
                "api")
            .contains(host)
        || Set.of(2375, 2376).contains(input.port())) {
      throw ApiException.invalid("元数据和平台基础设施管理目标不能授权");
    }
    var e = new TargetPolicyEntity();
    e.setProjectId(projectId);
    e.setScheme(input.scheme());
    e.setHost(host);
    e.setPort(input.port());
    e.setAddressScope(input.addressScope());
    e.setEnabled(true);
    e.setCreatedBy(actor.getId());
    e.setUpdatedBy(actor.getId());
    mapper.insert(e);
    audit.insert(projectId, actor.getId(), "TARGET_POLICY_CREATE", "TARGET_POLICY", e.getId());
    return view(mapper.find(projectId, e.getId()));
  }

  @Transactional
  public TargetPolicyDtos.View update(long projectId, long id, TargetPolicyDtos.Patch input) {
    var actor = current.admin();
    var e = mapper.find(projectId, id);
    if (e == null) {
      throw ApiException.missing();
    }
    if (e.getVersion() != input.version()) {
      throw ApiException.conflict();
    }
    e.setEnabled(input.enabled());
    e.setUpdatedBy(actor.getId());
    ServiceSupport.changed(mapper.update(e));
    audit.insert(projectId, actor.getId(), "TARGET_POLICY_UPDATE", "TARGET_POLICY", id);
    return view(mapper.find(projectId, id));
  }

  private TargetPolicyDtos.View view(TargetPolicyEntity e) {
    return new TargetPolicyDtos.View(
        e.getId().toString(),
        e.getScheme(),
        e.getHost(),
        e.getPort(),
        e.getAddressScope(),
        e.isEnabled(),
        e.getVersion());
  }
}
