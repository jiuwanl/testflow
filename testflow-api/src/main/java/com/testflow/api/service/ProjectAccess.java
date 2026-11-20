package com.testflow.api.service;

import com.testflow.api.security.CurrentAccount;
import com.testflow.api.web.ApiException;
import com.testflow.core.ProjectRole;
import com.testflow.persistence.entity.MemberEntity;
import com.testflow.persistence.entity.ProjectEntity;
import com.testflow.persistence.mapper.ProjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ProjectAccess {
  private final ProjectMapper projects;
  private final CurrentAccount current;

  public ProjectAccess(ProjectMapper projects, CurrentAccount current) {
    this.projects = projects;
    this.current = current;
  }

  public MemberEntity require(long projectId, ProjectRole role, boolean writable) {
    var user = current.get();
    MemberEntity member = projects.member(projectId, user.getId());
    ProjectEntity project = projects.findById(projectId);
    if (member == null || project == null) {
      throw ApiException.missing();
    }
    if (!ProjectRole.valueOf(member.getRole()).includes(role)) {
      throw new ApiException(403, "FORBIDDEN", "当前角色无此操作权限");
    }
    if (writable && !"ACTIVE".equals(project.getStatus())) {
      throw new ApiException(409, "STATE_CONFLICT", "项目已归档");
    }
    return member;
  }
}
