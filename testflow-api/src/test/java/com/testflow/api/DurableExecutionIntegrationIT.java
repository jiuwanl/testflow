package com.testflow.api;

import static org.assertj.core.api.Assertions.*;

import com.testflow.core.execution.*;
import com.testflow.persistence.RunStore;
import com.testflow.persistence.mapper.RunMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "testflow.execution.recovery-interval-ms=600000")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DurableExecutionIntegrationIT {
  static {
    String url = System.getenv("TF_DB_URL");
    if (url == null || !url.contains("/testflow_test?"))
      throw new IllegalStateException("Dedicated testflow_test database required");
  }

  @Value("${local.server.port}")
  int port;

  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired RunStore store;
  @Autowired RunMapper runs;
  @Autowired SnapshotCodec codec;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  @Autowired com.testflow.persistence.mapper.TargetPolicyMapper targetMapper;
  @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
  private ApiTestSession admin, maintainer;
  private String project, caseId, environment, policy, maintainerId, secret;

  @BeforeEach
  void fixtures() throws Exception {
    try (var cursor =
        redis.scan(
            org.springframework.data.redis.core.ScanOptions.scanOptions()
                .match("testflow-test:login:*")
                .count(100)
                .build())) {
      List<String> keys = new ArrayList<>();
      cursor.forEachRemaining(keys::add);
      if (!keys.isEmpty()) redis.delete(keys);
    }
    jdbc.update("UPDATE tf_test_run SET parent_run_id=NULL");
    for (String table :
        List.of(
            "tf_quality_failure",
            "tf_quality_run_fact",
            "tf_outbox_event",
            "tf_run_item_result",
            "tf_run_snapshot",
            "tf_run_item",
            "tf_test_run",
            "tf_plan_step",
            "tf_test_plan",
            "tf_api_case",
            "tf_environment",
            "tf_target_policy",
            "tf_project_member",
            "tf_project",
            "tf_audit_event",
            "tf_worker_node")) jdbc.update("DELETE FROM " + table);
    jdbc.update(
        "DELETE FROM tf_user WHERE username<>?", System.getenv("TF_BOOTSTRAP_ADMIN_USERNAME"));
    admin = new ApiTestSession(port, json);
    admin.login(
        System.getenv("TF_BOOTSTRAP_ADMIN_USERNAME"), System.getenv("TF_BOOTSTRAP_ADMIN_PASSWORD"));
    String owner = admin.call("GET", "/auth/me", null).data().path("id").asString();
    String password = UUID.randomUUID().toString();
    maintainerId =
        ok(
                admin.call(
                    "POST",
                    "/admin/users",
                    Map.of("username", "case-runner", "password", password, "globalAdmin", false)),
                201)
            .path("id")
            .asString();
    project =
        ok(admin.call("POST", "/projects", Map.of("name", "阶段2事务夹具", "ownerId", owner)), 201)
            .path("id")
            .asString();
    ok(admin.call("PUT", base() + "/members/" + maintainerId, Map.of("role", "MAINTAINER")), 200);
    maintainer = new ApiTestSession(port, json);
    maintainer.login("case-runner", password);
    secret = "synthetic-" + UUID.randomUUID();
    environment =
        ok(
                admin.call(
                    "POST",
                    base() + "/environments",
                    Map.of(
                        "name",
                        "冻结环境",
                        "baseUrl",
                        "http://127.0.0.1:8088",
                        "variables",
                        Map.of("marker", "before"),
                        "secretUpdates",
                        List.of(
                            Map.of("name", "credential", "operation", "SET", "value", secret)))),
                201)
            .path("id")
            .asString();
    caseId =
        ok(
                admin.call(
                    "POST",
                    base() + "/cases",
                    Map.of("name", "单用例夹具", "configSchemaVersion", 1, "config", config("/before"))),
                201)
            .path("id")
            .asString();
    policy =
        ok(
                admin.call(
                    "POST",
                    base() + "/target-policies",
                    Map.of(
                        "scheme",
                        "http",
                        "host",
                        "127.0.0.1",
                        "port",
                        8088,
                        "addressScope",
                        "LOOPBACK")),
                201)
            .path("id")
            .asString();
  }

  @AfterEach
  void cleanRuns() {
    for (String table :
        List.of(
            "tf_quality_failure",
            "tf_quality_run_fact",
            "tf_outbox_event",
            "tf_run_item_result",
            "tf_run_snapshot",
            "tf_run_item",
            "tf_test_run")) jdbc.update("DELETE FROM " + table);
  }

  private String base() {
    return "/projects/" + project;
  }

  private Map<String, Object> config(String path) {
    return Map.of(
        "request",
        Map.of(
            "method",
            "GET",
            "path",
            path,
            "query",
            List.of(Map.of("name", "marker", "value", "${env.marker}")),
            "headers",
            List.of(),
            "body",
            Map.of("type", "NONE")),
        "assertions",
        List.of(Map.of("id", "status", "type", "STATUS_CODE", "expected", 200)),
        "extractors",
        List.of(),
        "timeoutMs",
        15000);
  }

  private Map<String, Object> input() {
    return Map.of("sourceType", "CASE", "sourceId", caseId, "environmentId", environment);
  }

  private JsonNode ok(ApiTestSession.Reply reply, int expected) {
    assertThat(reply.status()).isEqualTo(expected);
    return reply.data();
  }

  private long submit() throws Exception {
    return Long.parseLong(
        ok(maintainer.call("POST", base() + "/runs", input(), UUID.randomUUID().toString()), 202)
            .path("id")
            .asString());
  }

  private RunLease claim() {
    var run = store.claim("transaction-fixture-worker", 30000);
    assertThat(run).isNotNull();
    return RunStore.lease(run);
  }

  private ExecutionResult syntheticPass() {
    return new ExecutionResult(
        "PASSED", null, 0, Map.of("fixture", "transaction-only; no HTTP request"), false, false);
  }

  @Test
  void submissionIsAtomicAndFrozenEvenAfterDefinitionChanges() throws Exception {
    String key = UUID.randomUUID().toString();
    long id =
        Long.parseLong(
            ok(maintainer.call("POST", base() + "/runs", input(), key), 202).path("id").asString());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM tf_run_item WHERE run_id=?", Integer.class, id))
        .isEqualTo(1);
    var stored = runs.snapshotFor(id);
    assertThat(stored.encryptedPayload().contains(secret)).isFalse();
    ok(
        admin.call(
            "PATCH",
            base() + "/cases/" + caseId,
            Map.of("version", 0, "config", config("/after"), "status", "ARCHIVED")),
        200);
    ok(
        admin.call(
            "PATCH",
            base() + "/environments/" + environment,
            Map.of("version", 0, "variables", Map.of("marker", "after"))),
        200);
    var snapshot = codec.decode(stored.encryptedPayload(), stored.contentHmac());
    assertThat(snapshot.variables().get("marker")).isEqualTo("before");
    assertThat(snapshot.steps().getFirst().config().at("/request/path").asString())
        .isEqualTo("/before");
    assertThat(
            ok(maintainer.call("POST", base() + "/runs", input(), key), 200).path("id").asString())
        .isEqualTo(Long.toString(id));
    assertThat(
            maintainer
                .call("POST", base() + "/runs", input(), UUID.randomUUID().toString())
                .status())
        .isEqualTo(409);
  }

  @Test
  void concurrentIdenticalSubmissionsCreateOneRunAndDifferentContentConflicts() throws Exception {
    String key = UUID.randomUUID().toString();
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<ApiTestSession.Reply> action =
          () -> maintainer.call("POST", base() + "/runs", input(), key);
      var first = executor.submit(action);
      var second = executor.submit(action);
      var a = first.get(20, TimeUnit.SECONDS);
      var b = second.get(20, TimeUnit.SECONDS);
      assertThat(List.of(a.status(), b.status())).containsExactlyInAnyOrder(202, 200);
      assertThat(a.data().path("id").asString()).isEqualTo(b.data().path("id").asString());
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_test_run", Integer.class)).isEqualTo(1);
    var different = new HashMap<>(input());
    different.put("overrides", Map.of("variables", Map.of("marker", "different")));
    assertThat(maintainer.call("POST", base() + "/runs", different, key).status()).isEqualTo(409);
  }

  @Test
  void validationFailsBeforeAnyDurableRunIsCreated() throws Exception {
    var secretOverride = new HashMap<>(input());
    secretOverride.put("overrides", Map.of("variables", Map.of("credential", "forbidden")));
    assertThat(
            maintainer
                .call("POST", base() + "/runs", secretOverride, UUID.randomUUID().toString())
                .status())
        .isEqualTo(400);
    var plan = new HashMap<>(input());
    plan.put("sourceType", "PLAN");
    assertThat(
            maintainer.call("POST", base() + "/runs", plan, UUID.randomUUID().toString()).status())
        .isEqualTo(404); // PLAN is supported in stage 3, but no such project plan exists.
    assertThat(maintainer.call("POST", base() + "/runs", input()).status()).isEqualTo(400);
    assertThat(maintainer.call("POST", base() + "/runs", input(), "invalid-key").status())
        .isEqualTo(400);
    ok(
        admin.call(
            "PATCH",
            base() + "/cases/" + caseId,
            Map.of("version", 0, "config", config("/${env.missing}"))),
        200);
    assertThat(
            maintainer
                .call("POST", base() + "/runs", input(), UUID.randomUUID().toString())
                .status())
        .isEqualTo(400);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_test_run", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_run_snapshot", Integer.class)).isZero();
  }

  @Test
  void cancelledQueuedRunIsNeverClaimedAndMetricsAreNotInvented() throws Exception {
    long id = submit();
    var cancelled = ok(maintainer.call("POST", base() + "/runs/" + id + "/cancel", Map.of()), 200);
    assertThat(cancelled.path("state").asString()).isEqualTo("CANCELLED");
    assertThat(cancelled.at("/metrics/passRate").isNull()).isTrue();
    assertThat(cancelled.at("/metrics/coverage").asDouble()).isZero();
    assertThat(cancelled.at("/metrics/cancelled").asInt()).isEqualTo(1);
    assertThat(store.claim("fixture", 30000)).isNull();
    assertThat(maintainer.call("POST", base() + "/runs/" + id + "/cancel", Map.of()).status())
        .isEqualTo(200);
  }

  @Test
  void expiredOwnershipCannotPersistOrOverwriteAnyResult() throws Exception {
    long id = submit();
    var lease = claim();
    assertThat(store.begin(lease)).isTrue();
    jdbc.update(
        "UPDATE tf_test_run SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",
        id);
    assertThat(store.recover(10)).isEqualTo(1);
    assertThat(store.finish(lease, syntheticPass(), null)).isFalse();
    var view = ok(maintainer.call("GET", base() + "/runs/" + id, null), 200);
    assertThat(view.path("state").asString()).isEqualTo("INTERRUPTED");
    assertThat(view.path("outcome").asString()).isEqualTo("INCOMPLETE");
    assertThat(view.at("/metrics/unknown").asInt()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_run_item_result", Integer.class))
        .isZero();
  }

  @Test
  void resultPersistenceIsIdempotentAndCancellationRetainsUncertainty() throws Exception {
    long id = submit();
    var lease = claim();
    assertThat(store.begin(lease)).isTrue();
    ok(maintainer.call("POST", base() + "/runs/" + id + "/cancel", Map.of()), 202);
    assertThat(store.pulse(lease, 30000)).isEqualTo(RunStore.Signal.CANCELLING);
    var aborted =
        new ExecutionResult(
            "UNKNOWN",
            "USER_CANCELLED",
            0,
            Map.of("fixture", "cancelled transaction"),
            false,
            false);
    assertThat(store.finish(lease, aborted, null)).isTrue();
    assertThat(store.finish(lease, syntheticPass(), null)).isFalse();
    var run = runs.find(id);
    assertThat(run.getState()).isEqualTo("CANCELLED");
    assertThat(run.getUnknownCount()).isEqualTo(1);
    assertThat(run.getPassedCount()).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_run_item_result", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void simultaneousClaimsAreDistinctAndDoNotPrefetchTheSameRun() throws Exception {
    submit();
    submit();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a = executor.submit(() -> store.claim("claim-a", 30000));
      var b = executor.submit(() -> store.claim("claim-b", 30000));
      var first = a.get(10, TimeUnit.SECONDS);
      var second = b.get(10, TimeUnit.SECONDS);
      assertThat(first).isNotNull();
      assertThat(second).isNotNull();
      assertThat(first.getId()).isNotEqualTo(second.getId());
    }
    assertThat(store.claim("claim-c", 30000)).isNull();
  }

  @Test
  void deadlinesConvergeAndLeaseExpiryHasPriority() throws Exception {
    long queued = submit();
    jdbc.update(
        "UPDATE tf_test_run SET deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",
        queued);
    assertThat(store.recover(10)).isEqualTo(1);
    assertThat(runs.find(queued).getState()).isEqualTo("TIMED_OUT");
    assertThat(runs.items(queued).getFirst().getState()).isEqualTo("SKIPPED");
    long running = submit();
    var lease = claim();
    store.begin(lease);
    jdbc.update(
        "UPDATE tf_test_run SET"
            + " deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)),lease_until=TIMESTAMPADD(SECOND,-2,UTC_TIMESTAMP(3))"
            + " WHERE id=?",
        running);
    store.recover(10);
    assertThat(runs.find(running).getState()).isEqualTo("INTERRUPTED");
  }

  @Test
  void currentAuthorizationAndTargetRevocationOverrideFrozenApproval() throws Exception {
    long id = submit();
    var lease = claim();
    var stored = runs.snapshotFor(id);
    var snapshot = codec.decode(stored.encryptedPayload(), stored.contentHmac());
    assertThat(store.permitted(lease, snapshot)).isTrue();
    ok(
        admin.call(
            "PATCH", base() + "/target-policies/" + policy, Map.of("version", 0, "enabled", false)),
        200);
    assertThat(store.permitted(lease, snapshot)).isFalse();
    ok(
        admin.call(
            "PATCH", base() + "/target-policies/" + policy, Map.of("version", 1, "enabled", true)),
        200);
    ok(admin.call("DELETE", base() + "/members/" + maintainerId, null), 200);
    assertThat(store.permitted(lease, snapshot)).isFalse();
  }

  @Test
  void reportAndCancellationKeepTheProjectBoundaryAndHideExecutionSecrets() throws Exception {
    long id = submit();
    String other =
        ok(
                admin.call(
                    "POST",
                    "/projects",
                    Map.of(
                        "name",
                        "另一个项目",
                        "ownerId",
                        admin.call("GET", "/auth/me", null).data().path("id").asString())),
                201)
            .path("id")
            .asString();
    assertThat(admin.call("GET", "/projects/" + other + "/runs/" + id, null).status())
        .isEqualTo(404);
    assertThat(
            admin.call("POST", "/projects/" + other + "/runs/" + id + "/cancel", Map.of()).status())
        .isEqualTo(404);
    assertThat(new ApiTestSession(port, json).call("GET", base() + "/runs/" + id, null).status())
        .isEqualTo(401);
    ok(
        admin.call(
            "PUT", base() + "/members/" + maintainerId, Map.of("version", 0, "role", "VIEWER")),
        200);
    assertThat(maintainer.call("POST", base() + "/runs/" + id + "/cancel", Map.of()).status())
        .isEqualTo(403);
    var report = ok(maintainer.call("GET", base() + "/runs/" + id + "/report", null), 200);
    String rendered = json.writeValueAsString(report);
    assertThat(rendered.contains(secret)).isFalse();
    assertThat(rendered)
        .doesNotContain("encryptedPayload", "contentHmac", "leaseVersion", "ownerId");
    assertThat(report.at("/run/createdAt").asString()).endsWith("Z");
  }

  @Test
  void duplicateFinalizationNeverIncrementsCountsTwice() throws Exception {
    long id = submit();
    var lease = claim();
    store.begin(lease);
    assertThat(store.finish(lease, syntheticPass(), null)).isTrue();
    assertThat(store.finish(lease, syntheticPass(), null)).isFalse();
    assertThat(runs.find(id).getPassedCount()).isEqualTo(1);
    assertThat(runs.find(id).getState()).isEqualTo("COMPLETED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_run_item_result", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void expiryDuringTheResultTransactionRollsBackItsChildWrites() throws Exception {
    long id = submit();
    var owned = store.claim("late-transaction-fixture", 1000);
    var lease = RunStore.lease(owned);
    assertThat(store.begin(lease)).isTrue();
    RunMapper delayed =
        (RunMapper)
            java.lang.reflect.Proxy.newProxyInstance(
                RunMapper.class.getClassLoader(),
                new Class<?>[] {RunMapper.class},
                (proxy, method, args) -> {
                  Object value;
                  try {
                    value = method.invoke(runs, args);
                  } catch (java.lang.reflect.InvocationTargetException ex) {
                    throw ex.getCause();
                  }
                  if (method.getName().equals("result")) Thread.sleep(1200);
                  return value;
                });
    var delayedStore = new RunStore(delayed, targetMapper, transactionManager, json);
    assertThatThrownBy(() -> delayedStore.finish(lease, syntheticPass(), null))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM tf_run_item_result WHERE item_id IN (SELECT id FROM"
                    + " tf_run_item WHERE run_id=?)",
                Integer.class,
                id))
        .isZero();
    assertThat(runs.find(id).getPassedCount()).isZero();
    store.recover(10);
    assertThat(runs.find(id).getState()).isEqualTo("INTERRUPTED");
    assertThat(runs.items(id).getFirst().getState()).isEqualTo("UNKNOWN");
  }

  @Test
  void cancellationAfterDeadlineCannotRewriteTimeoutAsUserCancellation() throws Exception {
    long id = submit();
    jdbc.update(
        "UPDATE tf_test_run SET deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",
        id);
    var result = ok(maintainer.call("POST", base() + "/runs/" + id + "/cancel", Map.of()), 200);
    assertThat(result.path("state").asString()).isEqualTo("TIMED_OUT");
    assertThat(runs.items(id).getFirst().getState()).isEqualTo("SKIPPED");
  }
}
