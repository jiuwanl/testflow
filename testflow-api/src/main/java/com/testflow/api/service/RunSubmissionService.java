package com.testflow.api.service;

import com.testflow.api.dto.RunDtos;
import com.testflow.api.security.CurrentAccount;
import com.testflow.api.web.ApiException;
import com.testflow.core.*;
import com.testflow.core.execution.*;
import com.testflow.persistence.entity.*;
import com.testflow.persistence.mapper.*;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class RunSubmissionService {
  private final TestPlanMapper plans;
  private final PlanStepMapper planSteps;
  private final RunMapper runs;
  private final ProjectMapper projects;
  private final ApiCaseMapper cases;
  private final EnvironmentMapper environments;
  private final TargetPolicyMapper targets;
  private final AuditMapper audit;
  private final CurrentAccount current;
  private final ProjectAccess access;
  private final JsonMapper json;
  private final SecretCipher secrets;
  private final SnapshotCodec codec;
  private final TargetGuard guard;
  private final TransactionTemplate transaction;
  private final long runTimeoutMs;

  public RunSubmissionService(
      TestPlanMapper plans,
      PlanStepMapper planSteps,
      RunMapper runs,
      ProjectMapper projects,
      ApiCaseMapper cases,
      EnvironmentMapper environments,
      TargetPolicyMapper targets,
      AuditMapper audit,
      CurrentAccount current,
      ProjectAccess access,
      JsonMapper json,
      SecretCipher secrets,
      SnapshotCodec codec,
      TargetGuard guard,
      PlatformTransactionManager manager,
      @Value("${testflow.execution.run-timeout-ms:600000}") long runTimeoutMs) {
    this.plans = plans;
    this.planSteps = planSteps;
    this.runs = runs;
    this.projects = projects;
    this.cases = cases;
    this.environments = environments;
    this.targets = targets;
    this.audit = audit;
    this.current = current;
    this.access = access;
    this.json = json;
    this.secrets = secrets;
    this.codec = codec;
    this.guard = guard;
    if (runTimeoutMs < 100 || runTimeoutMs > 1800000)
      throw new IllegalArgumentException("Invalid run timeout");
    this.runTimeoutMs = runTimeoutMs;
    transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(5);
  }

  public record Submission(RunEntity run, boolean created) {}

  public Submission submit(long projectId, String key, RunDtos.Submit input) {
    access.require(projectId, ProjectRole.MAINTAINER, false);
    String canonicalKey;
    try {
      canonicalKey = UUID.fromString(key).toString();
      if (!canonicalKey.equalsIgnoreCase(key)) throw new IllegalArgumentException();
    } catch (RuntimeException ex) {
      throw ApiException.invalid("Idempotency-Key 必须为标准 UUID");
    }
    if (!Set.of("CASE", "PLAN").contains(input.sourceType()))
      throw ApiException.invalid("不支持的执行来源");
    long caseId = ServiceSupport.id(input.sourceId()),
        envId = ServiceSupport.id(input.environmentId());
    Map<String, String> overrides =
        input.overrides() == null || input.overrides().variables() == null
            ? Map.of()
            : input.overrides().variables();
    Integer timeout = input.overrides() == null ? null : input.overrides().requestTimeoutMs();
    Map<String, Object> request = new TreeMap<>();
    request.put("sourceType", input.sourceType());
    request.put("sourceId", Long.toString(caseId));
    request.put("environmentId", Long.toString(envId));
    request.put("variables", overrides);
    request.put("requestTimeoutMs", timeout);
    String hash = codec.requestHash(request);
    long userId = current.get().getId();
    RunEntity existing = runs.byKey(projectId, userId, canonicalKey);
    if (existing != null) return replay(existing, hash);
    try {
      return transaction.execute(
          status -> {
            projects.lockById(projectId);
            access.require(projectId, ProjectRole.MAINTAINER, false);
            RunEntity winner = runs.byKey(projectId, userId, canonicalKey);
            if (winner != null) return replay(winner, hash);
            access.require(projectId, ProjectRole.MAINTAINER, true);
            EnvironmentEntity environment = environments.lock(projectId, envId);
            if (environment == null) throw ApiException.missing();
            if (!"ACTIVE".equals(environment.getStatus()))
              throw new ApiException(409, "STATE_CONFLICT", "环境已归档");
            TestPlanEntity plan = null;
            List<Long> ids;
            if ("PLAN".equals(input.sourceType())) {
              plan = plans.lock(projectId, caseId);
              if (plan == null) throw ApiException.missing();
              if (!"ACTIVE".equals(plan.getStatus())
                  || !Set.of("ORDERED", "INDEPENDENT").contains(plan.getExecutionMode()))
                throw new ApiException(409, "STATE_CONFLICT", "计划已归档或执行模式不受支持");
              ids =
                  planSteps.list(projectId, caseId).stream()
                      .filter(PlanStepEntity::isEnabled)
                      .map(PlanStepEntity::getCaseId)
                      .toList();
            } else ids = List.of(caseId);
            if (ids.isEmpty() || ids.size() > 100) throw ApiException.invalid("计划必须包含 1～100 个启用步骤");
            Map<Long, ApiCaseEntity> locked = new HashMap<>();
            for (long id : ids.stream().distinct().sorted().toList()) {
              var entry = cases.lock(projectId, id);
              if (entry == null) throw ApiException.missing();
              if (!"ACTIVE".equals(entry.getStatus()))
                throw new ApiException(409, "STATE_CONFLICT", "用例已归档");
              locked.put(id, entry);
            }
            var frozenSteps = new ArrayList<ExecutionSnapshot.Step>();
            var declared = new HashMap<String, tools.jackson.databind.JsonNode>();
            Map<String, String> variables =
                json.readValue(
                    environment.getVariablesJson(), new TypeReference<Map<String, String>>() {});
            Map<String, String> privateValues =
                json.readValue(
                    secrets.decrypt(environment.getSecretCiphertext()),
                    new TypeReference<Map<String, String>>() {});
            if (!Collections.disjoint(privateValues.keySet(), overrides.keySet()))
              throw ApiException.invalid("不能通过普通变量覆盖秘密");
            variables = new TreeMap<>(variables);
            variables.putAll(overrides);
            variables.putAll(privateValues);
            for (long id : ids) {
              var entry = locked.get(id);
              ObjectNode config = (ObjectNode) json.readTree(entry.getConfigJson());
              if (timeout != null) config.put("timeoutMs", timeout);
              try {
                if (plan != null && "INDEPENDENT".equals(plan.getExecutionMode()))
                  ExecutionPolicy.independent(config);
                DefinitionValidator.caseConfig(config);
                DefinitionValidator.caseConfig(
                    new VariableTemplate(json).bind(config, variables, declared));
              } catch (IllegalArgumentException | ExecutionFailure ex) {
                throw ApiException.invalid("请求配置、环境变量或前序运行变量引用不合法");
              }
              frozenSteps.add(
                  new ExecutionSnapshot.Step(id, entry.getName(), entry.getVersion(), config));
              for (var extractor : config.path("extractors"))
                declared.put(
                    extractor.path("name").asString(),
                    json.getNodeFactory().stringNode("validated-runtime-value"));
            }
            URI base = URI.create(environment.getBaseUrl());
            TargetPolicyEntity approved =
                targets.approved(
                    projectId, base.getScheme(), TargetGuard.host(base), TargetGuard.port(base));
            if (approved == null)
              throw new ApiException(403, "TARGET_NOT_AUTHORIZED", "该环境目标尚未获得管理员授权");
            var rule =
                new ExecutionSnapshot.TargetRule(
                    approved.getId(),
                    approved.getScheme(),
                    approved.getHost(),
                    approved.getPort(),
                    approved.getAddressScope());
            try {
              guard.authorize(base, List.of(rule));
            } catch (ExecutionFailure ex) {
              throw new ApiException(403, "TARGET_BLOCKED", "目标地址或端口不符合执行安全策略");
            }
            RunEntity run = new RunEntity();
            run.setProjectId(projectId);
            run.setRequestedBy(userId);
            run.setIdempotencyKey(canonicalKey);
            run.setRequestHash(hash);
            run.setKind(input.sourceType());
            run.setSourceId(caseId);
            run.setEnvId(envId);
            run.setSourceNameSnapshot(plan == null ? locked.get(caseId).getName() : plan.getName());
            run.setEnvironmentNameSnapshot(environment.getName());
            run.setRequestedByName(current.get().getUsername());
            run.setTotalCount(frozenSteps.size());
            run.setExecutionMode(plan == null ? "ORDERED" : plan.getExecutionMode());
            run.setMaxConcurrency(plan == null ? 1 : plan.getMaxConcurrency());
            ExecutionPolicy.validate(run.getExecutionMode(), run.getMaxConcurrency());
            runs.insert(run, runTimeoutMs);
            var snapshot =
                new ExecutionSnapshot(
                    3,
                    run.getId(),
                    projectId,
                    userId,
                    envId,
                    environment.getVersion(),
                    environment.getBaseUrl(),
                    variables,
                    privateValues.keySet(),
                    List.of(rule),
                    frozenSteps,
                    plan != null && plan.isFailFast(),
                    plan == null ? null : plan.getVersion(),
                    run.getExecutionMode(),
                    run.getMaxConcurrency());
            SnapshotCodec.Encoded encoded;
            try {
              encoded = codec.encode(snapshot);
            } catch (IllegalArgumentException ex) {
              throw new ApiException(413, "SNAPSHOT_TOO_LARGE", "执行快照超过限制");
            }
            runs.snapshot(run.getId(), projectId, encoded.hmac(), encoded.ciphertext());
            insertItems(run, snapshot);
            audit.insert(projectId, userId, "RUN_SUBMIT", "RUN", run.getId());
            return new Submission(runs.find(run.getId()), true);
          });
    } catch (DuplicateKeyException ex) {
      // The failed insertion transaction is over; read the committed winner in a fresh context.
      RunEntity winner = runs.byKey(projectId, userId, canonicalKey);
      if (winner == null) throw ex;
      return replay(winner, hash);
    }
  }

  private void insertItems(RunEntity run, ExecutionSnapshot snapshot) {
    int ordinal = 0;
    for (var step : snapshot.steps()) {
      var item = new RunItemEntity();
      item.setProjectId(run.getProjectId());
      item.setRunId(run.getId());
      item.setOrdinal(++ordinal);
      item.setSourceCaseId(step.caseId());
      item.setCaseNameSnapshot(step.name());
      runs.insertItem(item);
    }
  }

  public Submission rerun(long projectId, long originalId, String key) {
    access.require(projectId, ProjectRole.MAINTAINER, false);
    String canonicalKey;
    try {
      canonicalKey = UUID.fromString(key).toString();
      if (!canonicalKey.equalsIgnoreCase(key)) throw new IllegalArgumentException();
    } catch (RuntimeException ex) {
      throw ApiException.invalid("Idempotency-Key 必须为标准 UUID");
    }
    String hash =
        codec.requestHash(
            Map.of("operation", "SNAPSHOT_RERUN", "parentRunId", Long.toString(originalId)));
    long userId = current.get().getId();
    var existing = runs.byKey(projectId, userId, canonicalKey);
    if (existing != null) return replay(existing, hash);
    try {
      return transaction.execute(
          status -> {
            projects.lockById(projectId);
            access.require(projectId, ProjectRole.MAINTAINER, false);
            var winner = runs.byKey(projectId, userId, canonicalKey);
            if (winner != null) return replay(winner, hash);
            access.require(projectId, ProjectRole.MAINTAINER, true);
            var original = runs.find(originalId);
            if (original == null || original.getProjectId() != projectId)
              throw ApiException.missing();
            if (!com.testflow.persistence.RunStore.terminal(original.getState()))
              throw new ApiException(409, "RUN_NOT_TERMINAL", "原运行尚未结束");
            ExecutionSnapshot old;
            try {
              var stored = runs.snapshotFor(originalId);
              old = codec.decode(stored.encryptedPayload(), stored.contentHmac());
              if (old.runId() != originalId
                  || old.projectId() != projectId
                  || old.environmentId() != original.getEnvId()
                  || old.steps().size() != original.getTotalCount())
                throw new IllegalStateException();
            } catch (RuntimeException ex) {
              throw new ApiException(409, "SNAPSHOT_UNAVAILABLE", "原执行快照不可用，不能重跑");
            }
            for (var frozen : old.approvedTargets()) {
              var live = targets.approved(projectId, frozen.scheme(), frozen.host(), frozen.port());
              if (live == null
                  || live.getId() != frozen.id()
                  || !live.getAddressScope().equals(frozen.addressScope()))
                throw new ApiException(403, "TARGET_NOT_AUTHORIZED", "原目标授权已失效");
            }
            try {
              guard.authorize(URI.create(old.baseUrl()), old.approvedTargets());
            } catch (ExecutionFailure ex) {
              throw new ApiException(403, "TARGET_BLOCKED", "原目标不符合当前安全策略");
            }
            var run = new RunEntity();
            run.setProjectId(projectId);
            run.setRequestedBy(userId);
            run.setIdempotencyKey(canonicalKey);
            run.setRequestHash(hash);
            run.setKind(original.getKind());
            run.setSourceId(original.getSourceId());
            run.setEnvId(original.getEnvId());
            run.setParentRunId(originalId);
            run.setSourceNameSnapshot(original.getSourceNameSnapshot());
            run.setEnvironmentNameSnapshot(original.getEnvironmentNameSnapshot());
            run.setRequestedByName(current.get().getUsername());
            run.setTotalCount(old.steps().size());
            run.setExecutionMode(old.executionMode());
            run.setMaxConcurrency(old.maxConcurrency());
            runs.insert(run, runTimeoutMs);
            var snapshot =
                new ExecutionSnapshot(
                    3,
                    run.getId(),
                    projectId,
                    userId,
                    old.environmentId(),
                    old.environmentVersion(),
                    old.baseUrl(),
                    old.variables(),
                    old.secretNames(),
                    old.approvedTargets(),
                    old.steps(),
                    old.failFast(),
                    old.planVersion(),
                    old.executionMode(),
                    old.maxConcurrency());
            var encoded = codec.encode(snapshot);
            runs.snapshot(run.getId(), projectId, encoded.hmac(), encoded.ciphertext());
            insertItems(run, snapshot);
            audit.insert(projectId, userId, "RUN_RERUN", "RUN", run.getId());
            return new Submission(runs.find(run.getId()), true);
          });
    } catch (DuplicateKeyException ex) {
      var winner = runs.byKey(projectId, userId, canonicalKey);
      if (winner == null) throw ex;
      return replay(winner, hash);
    }
  }

  private Submission replay(RunEntity run, String hash) {
    if (!run.getRequestHash().equals(hash))
      throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "相同幂等键不能用于不同执行请求");
    return new Submission(run, false);
  }
}
