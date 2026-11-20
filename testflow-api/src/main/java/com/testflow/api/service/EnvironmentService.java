package com.testflow.api.service;

import com.testflow.api.dto.EnvironmentDtos;
import com.testflow.api.security.CurrentAccount;
import com.testflow.api.web.*;
import com.testflow.core.*;
import com.testflow.persistence.entity.EnvironmentEntity;
import com.testflow.persistence.mapper.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EnvironmentService {
  private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
  private final EnvironmentMapper mapper;
  private final ProjectAccess access;
  private final CurrentAccount current;
  private final AuditMapper audit;
  private final SecretCipher cipher;
  private final JsonMapper json;

  public EnvironmentService(
      EnvironmentMapper mapper,
      ProjectAccess access,
      CurrentAccount current,
      AuditMapper audit,
      SecretCipher cipher,
      JsonMapper json) {
    this.mapper = mapper;
    this.access = access;
    this.current = current;
    this.audit = audit;
    this.cipher = cipher;
    this.json = json;
  }

  public PageResult<EnvironmentDtos.Summary> list(
      long projectId, String name, String status, int page, int size) {
    access.require(projectId, ProjectRole.VIEWER, false);
    int offset = PageResult.offset(page, size);
    name = ServiceSupport.filterName(name);
    status = ServiceSupport.filterStatus(status);
    return new PageResult<>(
        mapper.list(projectId, name, status, offset, size).stream()
            .map(
                e ->
                    new EnvironmentDtos.Summary(
                        e.getId().toString(), e.getName(), e.getStatus(), e.getVersion()))
            .toList(),
        page,
        size,
        mapper.count(projectId, name, status));
  }

  public EnvironmentDtos.View get(long projectId, long id) {
    access.require(projectId, ProjectRole.VIEWER, false);
    return view(find(projectId, id));
  }

  @Transactional
  public EnvironmentDtos.View create(long projectId, EnvironmentDtos.Create input) {
    access.require(projectId, ProjectRole.MAINTAINER, true);
    var e = new EnvironmentEntity();
    e.setProjectId(projectId);
    e.setName(ServiceSupport.name(input.name()));
    e.setBaseUrl(url(input.baseUrl()));
    e.setVariablesJson(json.writeValueAsString(input.variables()));
    var secrets = applySecrets(new TreeMap<>(), input.secretUpdates(), input.variables());
    e.setSecretCiphertext(cipher.encrypt(json.writeValueAsString(secrets)));
    e.setKeyVersion("v1");
    e.setStatus("ACTIVE");
    e.setCreatedBy(current.get().getId());
    e.setUpdatedBy(current.get().getId());
    mapper.insert(e);
    audit.insert(projectId, current.get().getId(), "ENVIRONMENT_CREATE", "ENVIRONMENT", e.getId());
    return view(find(projectId, e.getId()));
  }

  @Transactional
  public EnvironmentDtos.View update(long projectId, long id, EnvironmentDtos.Patch input) {
    access.require(projectId, ProjectRole.MAINTAINER, true);
    var e = find(projectId, id);
    if (e.getVersion() != input.version()) {
      throw ApiException.conflict();
    }
    if (input.name() != null) {
      e.setName(ServiceSupport.name(input.name()));
    }
    if (input.baseUrl() != null) {
      e.setBaseUrl(url(input.baseUrl()));
    }
    Map<String, String> variables =
        input.variables() != null
            ? input.variables()
            : json.readValue(e.getVariablesJson(), STRING_MAP);
    var secrets = json.readValue(cipher.decrypt(e.getSecretCiphertext()), STRING_MAP);
    secrets = applySecrets(new TreeMap<>(secrets), input.secretUpdates(), variables);
    e.setVariablesJson(json.writeValueAsString(variables));
    e.setSecretCiphertext(cipher.encrypt(json.writeValueAsString(secrets)));
    if (input.status() != null) {
      e.setStatus(input.status());
    }
    e.setUpdatedBy(current.get().getId());
    ServiceSupport.changed(mapper.update(e));
    audit.insert(projectId, current.get().getId(), "ENVIRONMENT_UPDATE", "ENVIRONMENT", e.getId());
    return view(find(projectId, id));
  }

  private Map<String, String> applySecrets(
      Map<String, String> secrets,
      List<EnvironmentDtos.SecretUpdate> changes,
      Map<String, String> variables) {
    Set<String> changed = new HashSet<>();
    if (changes != null) {
      for (var change : changes) {
        if (!changed.add(change.name())) {
          throw ApiException.invalid("同一个秘密只能更新一次");
        }
        if ("REMOVE".equals(change.operation())) {
          secrets.remove(change.name());
        } else {
          if (change.value() == null || change.value().isBlank()) {
            throw ApiException.invalid("SET 秘密时必须提供非空值");
          }
          secrets.put(change.name(), change.value());
        }
      }
    }
    if (secrets.size() > 100 || !Collections.disjoint(secrets.keySet(), variables.keySet())) {
      throw ApiException.invalid("秘密不得超过 100 个，且不能与普通变量重名");
    }
    return secrets;
  }

  private EnvironmentEntity find(long projectId, long id) {
    var e = mapper.find(projectId, id);
    if (e == null) {
      throw ApiException.missing();
    }
    return e;
  }

  private EnvironmentDtos.View view(EnvironmentEntity e) {
    Map<String, String> secrets =
        json.readValue(cipher.decrypt(e.getSecretCiphertext()), STRING_MAP);
    return new EnvironmentDtos.View(
        e.getId().toString(),
        e.getName(),
        e.getBaseUrl(),
        json.readValue(e.getVariablesJson(), STRING_MAP),
        secrets.keySet().stream().sorted().toList(),
        e.getStatus(),
        e.getVersion());
  }

  private String url(String value) {
    try {
      return DefinitionValidator.baseUrl(value);
    } catch (IllegalArgumentException ex) {
      throw ApiException.invalid(ex.getMessage());
    }
  }
}
