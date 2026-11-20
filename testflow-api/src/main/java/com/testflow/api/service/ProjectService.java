package com.testflow.api.service;

import com.testflow.api.dto.ProjectDtos;
import com.testflow.api.security.CurrentAccount;
import com.testflow.api.web.*;
import com.testflow.core.ProjectRole;
import com.testflow.persistence.entity.*;
import com.testflow.persistence.mapper.*;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProjectService {
  private final ProjectMapper projects;
  private final UserMapper users;
  private final AuditMapper audit;
  private final CurrentAccount current;
  private final ProjectAccess access;
  private final GovernanceTransactions transactions;
  private final ProjectOwnership ownership;

  public ProjectService(
      ProjectMapper projects,
      UserMapper users,
      AuditMapper audit,
      CurrentAccount current,
      ProjectAccess access,
      GovernanceTransactions transactions,
      ProjectOwnership ownership) {
    this.transactions = transactions;
    this.ownership = ownership;
    this.projects = projects;
    this.users = users;
    this.audit = audit;
    this.current = current;
    this.access = access;
  }

  public PageResult<ProjectDtos.View> list(int page, int size) {
    var user = current.get();
    int offset = PageResult.offset(page, size);
    return new PageResult<>(
        projects.list(user.getId(), offset, size).stream()
            .map(p -> ProjectDtos.View.from(p, p.getCurrentRole()))
            .toList(),
        page,
        size,
        projects.count(user.getId()));
  }

  public ProjectDtos.View get(long id) {
    var member = access.require(id, ProjectRole.VIEWER, false);
    return ProjectDtos.View.from(projects.findById(id), member.getRole());
  }

  public ProjectDtos.View create(ProjectDtos.Create input) {
    current.admin();
    return transactions.execute(
        List.of(ServiceSupport.id(input.ownerId())), false, () -> createLocked(input));
  }

  private ProjectDtos.View createLocked(ProjectDtos.Create input) {
    var actor = current.admin();
    long ownerId = ServiceSupport.id(input.ownerId());
    var owner = users.findById(ownerId);
    if (owner == null || !"ACTIVE".equals(owner.getStatus())) {
      throw ApiException.invalid("OWNER 账号不存在或未启用");
    }
    ProjectEntity p = new ProjectEntity();
    p.setName(ServiceSupport.name(input.name()));
    p.setStatus("ACTIVE");
    p.setCreatedBy(actor.getId());
    p.setUpdatedBy(actor.getId());
    projects.insert(p);
    MemberEntity member = new MemberEntity();
    member.setProjectId(p.getId());
    member.setUserId(ownerId);
    member.setRole("OWNER");
    member.setCreatedBy(actor.getId());
    member.setUpdatedBy(actor.getId());
    projects.insertMember(member);
    audit.insert(p.getId(), actor.getId(), "PROJECT_CREATE", "PROJECT", p.getId());
    return ProjectDtos.View.from(
        projects.findById(p.getId()), actor.getId() == ownerId ? "OWNER" : null);
  }

  public ProjectDtos.View update(long id, ProjectDtos.Patch input) {
    access.require(id, ProjectRole.OWNER, false);
    return transactions.execute(List.of(), false, () -> updateLocked(id, input));
  }

  private ProjectDtos.View updateLocked(long id, ProjectDtos.Patch input) {
    var p = projects.lockById(id);
    var member = access.require(id, ProjectRole.OWNER, false);
    if (p.getVersion() != input.version()) {
      throw ApiException.conflict();
    }
    if (input.name() != null) {
      p.setName(ServiceSupport.name(input.name()));
    }
    if (input.status() != null) {
      p.setStatus(input.status());
    }
    ownership.requireOwner(p);
    p.setUpdatedBy(current.get().getId());
    ServiceSupport.changed(projects.update(p));
    audit.insert(id, current.get().getId(), "PROJECT_UPDATE", "PROJECT", id);
    return ProjectDtos.View.from(projects.findById(id), member.getRole());
  }

  public PageResult<ProjectDtos.MemberView> members(long id, int page, int size) {
    access.require(id, ProjectRole.OWNER, false);
    int offset = PageResult.offset(page, size);
    return new PageResult<>(
        projects.members(id, offset, size).stream().map(ProjectDtos.MemberView::from).toList(),
        page,
        size,
        projects.memberCount(id));
  }

  public ProjectDtos.MemberView member(long projectId, long userId) {
    access.require(projectId, ProjectRole.OWNER, false);
    var m = projects.member(projectId, userId);
    var u = users.findById(userId);
    if (m == null || u == null) {
      throw ApiException.missing();
    }
    m.setUsername(u.getUsername());
    m.setUserStatus(u.getStatus());
    return ProjectDtos.MemberView.from(m);
  }

  public ProjectDtos.MemberView changeMember(
      long projectId, long userId, ProjectDtos.MemberChange input) {
    access.require(projectId, ProjectRole.OWNER, true);
    return transactions.execute(
        List.of(userId), false, () -> changeMemberLocked(projectId, userId, input));
  }

  private ProjectDtos.MemberView changeMemberLocked(
      long projectId, long userId, ProjectDtos.MemberChange input) {
    var p = projects.lockById(projectId);
    access.require(projectId, ProjectRole.OWNER, true);
    var target = users.findById(userId);
    if (target == null) {
      throw ApiException.invalid("成员账号不存在或未启用");
    }

    var existing = projects.member(projectId, userId);
    if (!"ACTIVE".equals(target.getStatus()) && (existing == null || "OWNER".equals(input.role())))
      throw ApiException.invalid("禁用账号不能被加入项目或提升为 OWNER");
    if (existing != null) {
      if (input.version() == null || existing.getVersion() != input.version()) {
        throw ApiException.conflict();
      }
      if (existing.getRole().equals(input.role())) {
        existing.setUsername(target.getUsername());
        existing.setUserStatus(target.getStatus());
        return ProjectDtos.MemberView.from(existing);
      }
      protectLastOwner(projectId, existing, input.role());
      existing.setRole(input.role());
      existing.setUpdatedBy(current.get().getId());
      ServiceSupport.changed(projects.updateMember(existing));
    } else {
      if (input.version() != null) {
        throw ApiException.conflict();
      }
      var m = new MemberEntity();
      m.setProjectId(projectId);
      m.setUserId(userId);
      m.setRole(input.role());
      m.setCreatedBy(current.get().getId());
      m.setUpdatedBy(current.get().getId());
      projects.insertMember(m);
    }
    ownership.requireOwner(p);
    ownership.touch(p, current.get().getId());
    audit.insert(projectId, current.get().getId(), "MEMBER_UPDATE", "USER", userId);
    var result = projects.member(projectId, userId);
    result.setUsername(target.getUsername());
    result.setUserStatus(target.getStatus());
    return ProjectDtos.MemberView.from(result);
  }

  public void removeMember(long projectId, long userId) {
    access.require(projectId, ProjectRole.OWNER, true);
    transactions.execute(
        List.of(userId),
        false,
        () -> {
          removeMemberLocked(projectId, userId);
          return null;
        });
  }

  private void removeMemberLocked(long projectId, long userId) {
    var p = projects.lockById(projectId);
    access.require(projectId, ProjectRole.OWNER, true);

    var m = projects.member(projectId, userId);
    if (m == null) {
      throw ApiException.missing();
    }
    protectLastOwner(projectId, m, null);
    projects.removeMember(projectId, userId);
    ownership.requireOwner(p);
    ownership.touch(p, current.get().getId());
    audit.insert(projectId, current.get().getId(), "MEMBER_REMOVE", "USER", userId);
  }

  private void protectLastOwner(long projectId, MemberEntity existing, String next) {
    if ("ACTIVE".equals(users.findById(existing.getUserId()).getStatus())
        && "OWNER".equals(existing.getRole())
        && !"OWNER".equals(next)
        && projects.activeOwners(projectId) <= 1) {
      throw new ApiException(409, "LAST_OWNER", "不能移除最后一名有效 OWNER");
    }
  }
}
