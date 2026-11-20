package com.testflow.api.service;

import com.testflow.api.config.ApiProperties;
import com.testflow.api.dto.OwnershipDtos;
import com.testflow.api.dto.UserDtos;
import com.testflow.api.security.CurrentAccount;
import com.testflow.api.web.*;
import com.testflow.persistence.entity.ProjectEntity;
import com.testflow.persistence.entity.UserEntity;
import com.testflow.persistence.mapper.*;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService implements ApplicationRunner {
  private final UserMapper users;
  private final AuditMapper audit;
  private final CurrentAccount current;
  private final PasswordEncoder passwords;
  private final ApiProperties properties;
  private final String dummyHash;
  private final GovernanceTransactions transactions;
  private final ProjectMapper projects;
  private final ProjectOwnership ownership;

  public AccountService(
      UserMapper users,
      AuditMapper audit,
      CurrentAccount current,
      PasswordEncoder passwords,
      ApiProperties properties,
      GovernanceTransactions transactions,
      ProjectMapper projects,
      ProjectOwnership ownership) {
    this.transactions = transactions;
    this.projects = projects;
    this.ownership = ownership;
    this.users = users;
    this.audit = audit;
    this.current = current;
    this.passwords = passwords;
    this.properties = properties;
    this.dummyHash = passwords.encode(java.util.UUID.randomUUID().toString());
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (users.count() != 0) {
      return;
    }
    String username = properties.bootstrapAdminUsername();
    String password = properties.bootstrapAdminPassword();
    if (username == null || !username.matches("[A-Za-z0-9][A-Za-z0-9_.-]{2,31}")) {
      throw new IllegalStateException(
          "Empty database requires TF_BOOTSTRAP_ADMIN_USERNAME and TF_BOOTSTRAP_ADMIN_PASSWORD");
    }
    try {
      ServiceSupport.password(password);
    } catch (ApiException ex) {
      throw new IllegalStateException(
          "Empty database requires a valid external bootstrap password");
    }
    UserEntity admin = new UserEntity();
    admin.setUsername(ServiceSupport.username(username));
    admin.setPasswordHash(passwords.encode(password));
    admin.setGlobalAdmin(true);
    admin.setStatus("ACTIVE");
    users.insert(admin);
  }

  public UserEntity authenticate(String username, String password) {
    UserEntity user = users.findByUsername(ServiceSupport.username(username));
    String hash = user == null ? dummyHash : user.getPasswordHash();
    boolean valid =
        password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72
            && passwords.matches(password, hash);
    if (!valid || user == null || !"ACTIVE".equals(user.getStatus())) {
      throw new ApiException(401, "BAD_CREDENTIALS", "账号或密码不正确");
    }
    return user;
  }

  public PageResult<UserDtos.View> list(int page, int size) {
    current.admin();
    int offset = PageResult.offset(page, size);
    return new PageResult<>(
        users.list(offset, size).stream().map(UserDtos.View::from).toList(),
        page,
        size,
        users.count());
  }

  public UserDtos.View create(UserDtos.Create input) {
    current.admin();
    return transactions.execute(List.of(), false, () -> createLocked(input));
  }

  private UserDtos.View createLocked(UserDtos.Create input) {
    var actor = current.admin();
    ServiceSupport.password(input.password());
    UserEntity u = new UserEntity();
    u.setUsername(ServiceSupport.username(input.username()));
    u.setPasswordHash(passwords.encode(input.password()));
    u.setGlobalAdmin(input.globalAdmin());
    u.setStatus("ACTIVE");
    u.setCreatedBy(actor.getId());
    u.setUpdatedBy(actor.getId());
    users.insert(u);
    audit.insert(null, actor.getId(), "USER_CREATE", "USER", u.getId());
    return UserDtos.View.from(users.findById(u.getId()));
  }

  public OwnershipDtos.Impact disableImpact(long id) {
    current.admin();
    var user = users.findById(id);
    if (user == null) throw ApiException.missing();
    return new OwnershipDtos.Impact(Long.toString(id), user.getVersion(), impactProjects(id));
  }

  private List<OwnershipDtos.ImpactProject> impactProjects(long id) {
    var user = users.findById(id);
    int subtract = "ACTIVE".equals(user.getStatus()) ? 1 : 0;
    return projects.activeOwnedProjects(id).stream()
        .map(
            p -> {
              int remaining = projects.activeOwners(p.getId()) - subtract;
              return new OwnershipDtos.ImpactProject(
                  p.getId().toString(), p.getName(), p.getVersion(), remaining, remaining < 1);
            })
        .toList();
  }

  public UserDtos.View update(long id, UserDtos.Patch input) {
    current.admin();
    var transfers =
        input.ownershipTransfers() == null
            ? List.<OwnershipDtos.Transfer>of()
            : input.ownershipTransfers();
    if (!transfers.isEmpty() && !"DISABLED".equals(input.status()))
      throw ApiException.invalid("仅禁用账号时可提交所有权交接");
    var ids = new ArrayList<Long>();
    ids.add(id);
    var byProject = new TreeMap<Long, OwnershipDtos.Transfer>();
    for (var t : transfers) {
      long projectId = ServiceSupport.id(t.projectId());
      long successor = ServiceSupport.id(t.successorUserId());
      if (successor == id || byProject.put(projectId, t) != null)
        throw ApiException.invalid("交接项目重复或继任者为原账号");
      ids.add(successor);
    }
    return transactions.execute(
        ids,
        true,
        () -> {
          var actor = current.admin();
          var u = users.findById(id);
          if (u.getVersion() != input.version()) throw ApiException.conflict();
          var owned = projects.activeOwnedProjects(id);
          var locked = new TreeMap<Long, ProjectEntity>();
          for (var p : owned) locked.put(p.getId(), projects.lockById(p.getId()));
          if (!locked.keySet().containsAll(byProject.keySet()))
            throw ApiException.invalid("交接包含无关或非活动项目");
          if (!transfers.isEmpty() && !"ACTIVE".equals(u.getStatus()))
            throw ApiException.invalid("账号已禁用，请使用项目所有权治理入口");
          if ("DISABLED".equals(input.status()) && "ACTIVE".equals(u.getStatus())) {
            if (u.isGlobalAdmin() && users.activeAdminIds().size() <= 1)
              throw new ApiException(409, "LAST_ADMIN", "不能禁用最后一名管理员");
            var missing = new ArrayList<OwnershipDtos.ImpactProject>();
            for (var p : locked.values()) {
              if (!"ACTIVE".equals(p.getStatus())) continue;
              int remaining = projects.activeOwners(p.getId()) - 1;
              var t = byProject.get(p.getId());
              if (t != null && p.getVersion() != t.version()) throw ApiException.conflict();
              if (remaining < 1 && t == null)
                missing.add(
                    new OwnershipDtos.ImpactProject(
                        p.getId().toString(), p.getName(), p.getVersion(), remaining, true));
            }
            if (!missing.isEmpty())
              throw new ApiException(409, "OWNER_TRANSFER_REQUIRED", "部分项目需要指定有效继任者")
                  .details(missing);
            for (var entry : byProject.entrySet()) {
              var p = locked.get(entry.getKey());
              if (!"ACTIVE".equals(p.getStatus())) throw ApiException.conflict();
              ownership.transfer(
                  p,
                  id,
                  ServiceSupport.id(entry.getValue().successorUserId()),
                  actor.getId(),
                  "账号禁用交接");
            }
          }
          if (input.status() != null) u.setStatus(input.status());
          if (input.password() != null) {
            ServiceSupport.password(input.password());
            u.setPasswordHash(passwords.encode(input.password()));
          }
          u.setUpdatedBy(actor.getId());
          ServiceSupport.changed(users.update(u));
          for (var p : locked.values()) ownership.requireOwner(p);
          audit.insertDetail(
              null,
              actor.getId(),
              "USER_UPDATE",
              "USER",
              id,
              "{\"status\":\"" + u.getStatus() + "\",\"transferCount\":" + transfers.size() + "}");
          return UserDtos.View.from(users.findById(id));
        });
  }
}
