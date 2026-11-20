package com.testflow.api.service;

import com.testflow.api.dto.OwnershipDtos;
import com.testflow.api.web.*;
import com.testflow.persistence.entity.*;
import com.testflow.persistence.mapper.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Called inside a governance transaction after user rows, then project rows, are locked. */
@Component
public class ProjectOwnership {
  private final ProjectMapper projects;
  private final UserMapper users;
  private final AuditMapper audit;
  private final JsonMapper json;

  public ProjectOwnership(
      ProjectMapper projects, UserMapper users, AuditMapper audit, JsonMapper json) {
    this.projects = projects;
    this.users = users;
    this.audit = audit;
    this.json = json;
  }

  public OwnershipDtos.Project summary(ProjectEntity p) {
    return new OwnershipDtos.Project(
        p.getId().toString(),
        p.getName(),
        p.getStatus(),
        p.getVersion(),
        projects.activeOwners(p.getId()));
  }

  public OwnershipDtos.Detail detail(long id) {
    var p = projects.findById(id);
    if (p == null) throw ApiException.missing();
    return new OwnershipDtos.Detail(
        summary(p),
        projects.owners(id).stream()
            .map(
                m ->
                    new OwnershipDtos.Owner(
                        m.getUserId().toString(),
                        m.getUsername(),
                        m.getUserStatus(),
                        m.getVersion()))
            .toList());
  }

  public void requireOwner(ProjectEntity p) {
    if ("ACTIVE".equals(p.getStatus()) && projects.activeOwners(p.getId()) < 1)
      throw new ApiException(409, "LAST_OWNER", "活动项目必须保留至少一名有效 OWNER");
  }

  public void touch(ProjectEntity p, long actor) {
    p.setUpdatedBy(actor);
    ServiceSupport.changed(projects.update(p));
    p.setVersion(p.getVersion() + 1);
  }

  public void transfer(ProjectEntity p, Long from, long successor, long actor, String reason) {
    if (Objects.equals(from, successor)) throw ApiException.invalid("继任者不能是原 OWNER 本人");
    var user = users.findById(successor);
    if (user == null || !"ACTIVE".equals(user.getStatus()))
      throw ApiException.invalid("继任者必须是启用状态的账号");
    var source = from == null ? null : projects.member(p.getId(), from);
    if (from != null && (source == null || !"OWNER".equals(source.getRole())))
      throw new ApiException(409, "STATE_CONFLICT", "原 OWNER 已变化，请刷新后重试");
    var target = projects.member(p.getId(), successor);
    String previous = target == null ? "NONE" : target.getRole();
    if (target == null) {
      target = new MemberEntity();
      target.setProjectId(p.getId());
      target.setUserId(successor);
      target.setRole("OWNER");
      target.setCreatedBy(actor);
      target.setUpdatedBy(actor);
      projects.insertMember(target);
    } else if (!"OWNER".equals(target.getRole())) {
      target.setRole("OWNER");
      target.setUpdatedBy(actor);
      ServiceSupport.changed(projects.updateMember(target));
    }
    if (source != null) {
      source.setRole("MAINTAINER");
      source.setUpdatedBy(actor);
      ServiceSupport.changed(projects.updateMember(source));
    }
    requireOwner(p);
    if (!"OWNER".equals(previous) || source != null) touch(p, actor);
    var summary = new LinkedHashMap<String, Object>();
    summary.put("reason", reason);
    summary.put("fromUserId", from == null ? null : from.toString());
    summary.put("successorUserId", Long.toString(successor));
    summary.put("successorPreviousRole", previous);
    summary.put("successorRole", "OWNER");
    summary.put("sourceRole", source == null ? null : "MAINTAINER");
    audit.insertDetail(
        p.getId(),
        actor,
        "OWNERSHIP_TRANSFER",
        "PROJECT",
        p.getId(),
        json.writeValueAsString(summary));
  }
}
