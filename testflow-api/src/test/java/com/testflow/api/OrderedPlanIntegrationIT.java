package com.testflow.api;

import static org.assertj.core.api.Assertions.*;

import com.testflow.core.execution.*;
import com.testflow.persistence.*;
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
class OrderedPlanIntegrationIT {
  static {
    if (System.getenv("TF_DB_URL") == null
        || !System.getenv("TF_DB_URL").contains("/testflow_test?"))
      throw new IllegalStateException("Dedicated test database required");
  }

  @Value("${local.server.port}")
  int port;

  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired RunMapper runs;
  @Autowired RunStore store;
  @Autowired SnapshotCodec codec;
  @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
  ApiTestSession admin;
  String base, project, env, first, second, policy, plan;

  @BeforeEach
  void setup() throws Exception {
    try (var cursor =
        redis.scan(
            org.springframework.data.redis.core.ScanOptions.scanOptions()
                .match("testflow-test:login:*")
                .count(100)
                .build())) {
      var keys = new ArrayList<String>();
      cursor.forEachRemaining(keys::add);
      if (!keys.isEmpty()) redis.delete(keys);
    }
    clearRuns();
    admin = new ApiTestSession(port, json);
    admin.login(
        System.getenv("TF_BOOTSTRAP_ADMIN_USERNAME"), System.getenv("TF_BOOTSTRAP_ADMIN_PASSWORD"));
    var owner = ok(admin.call("GET", "/auth/me", null), 200).path("id").asString();
    project =
        ok(admin.call("POST", "/projects", Map.of("name", "阶段3事务夹具", "ownerId", owner)), 201)
            .path("id")
            .asString();
    base = "/projects/" + project;
    env =
        ok(
                admin.call(
                    "POST",
                    base + "/environments",
                    Map.of(
                        "name",
                        "冻结环境",
                        "baseUrl",
                        "http://127.0.0.1:8088",
                        "variables",
                        Map.of("marker", "before"))),
                201)
            .path("id")
            .asString();
    policy =
        ok(
                admin.call(
                    "POST",
                    base + "/target-policies",
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
    first = createCase("first", config("/first", true, false));
    second = createCase("second", config("/next/${run.orderId}", false, true));
    plan = createPlan(true, List.of(first, second, second));
  }

  @AfterEach
  void clearRuns() {
    jdbc.update("UPDATE tf_test_run SET parent_run_id=NULL");
    for (String t :
        List.of(
            "tf_quality_failure",
            "tf_quality_run_fact",
            "tf_outbox_event",
            "tf_run_item_result",
            "tf_run_snapshot",
            "tf_run_item",
            "tf_test_run")) jdbc.update("DELETE FROM " + t);
  }

  JsonNode ok(ApiTestSession.Reply reply, int status) {
    assertThat(reply.status()).isEqualTo(status);
    return reply.data();
  }

  Object config(String path, boolean extract, boolean expected) {
    return Map.of(
        "request",
        Map.of(
            "method",
            "GET",
            "path",
            path,
            "query",
            List.of(),
            "headers",
            List.of(),
            "body",
            Map.of("type", "NONE")),
        "assertions",
        List.of(Map.of("id", "status", "type", "STATUS_CODE", "expected", 200)),
        "extractors",
        extract
            ? List.of(Map.of("name", "orderId", "path", "$.data.id", "secret", false))
            : List.of());
  }

  String createCase(String name, Object config) throws Exception {
    return ok(
            admin.call(
                "POST",
                base + "/cases",
                Map.of("name", name, "configSchemaVersion", 1, "config", config)),
            201)
        .path("id")
        .asString();
  }

  String createPlan(boolean fast, List<String> ids) throws Exception {
    return ok(
            admin.call(
                "POST",
                base + "/plans",
                Map.of(
                    "name",
                    "有序计划",
                    "executionMode",
                    "ORDERED",
                    "failFast",
                    fast,
                    "steps",
                    ids.stream().map(id -> Map.of("caseId", id, "enabled", true)).toList())),
            201)
        .path("id")
        .asString();
  }

  Map<String, Object> input() {
    return Map.of("sourceType", "PLAN", "sourceId", plan, "environmentId", env);
  }

  long submit() throws Exception {
    return Long.parseLong(
        ok(admin.call("POST", base + "/runs", input(), UUID.randomUUID().toString()), 202)
            .path("id")
            .asString());
  }

  ExecutionSnapshot snapshot(long id) {
    var row = runs.snapshotFor(id);
    return codec.decode(row.encryptedPayload(), row.contentHmac());
  }

  ExecutionResult result(String state) {
    return new ExecutionResult(state, null, 5, Map.of("synthetic", true), false, false);
  }

  @Test
  void freezesEnabledOrderDuplicatesAndOptionsBeforeDefinitionsChange() throws Exception {
    long id = submit();
    var snap = snapshot(id);
    assertThat(snap.schemaVersion()).isEqualTo(3);
    assertThat(snap.failFast()).isTrue();
    assertThat(snap.steps()).hasSize(3);
    assertThat(snap.steps().stream().map(ExecutionSnapshot.Step::caseId))
        .containsExactly(Long.parseLong(first), Long.parseLong(second), Long.parseLong(second));
    ok(
        admin.call(
            "PATCH",
            base + "/plans/" + plan,
            Map.of(
                "version",
                0,
                "steps",
                List.of(Map.of("caseId", second, "enabled", true)),
                "failFast",
                false)),
        200);
    ok(
        admin.call(
            "PATCH",
            base + "/cases/" + first,
            Map.of("version", 0, "config", config("/edited", false, false), "status", "ARCHIVED")),
        200);
    ok(
        admin.call(
            "PATCH",
            base + "/environments/" + env,
            Map.of("version", 0, "variables", Map.of("marker", "after"))),
        200);
    assertThat(snapshot(id)).isEqualTo(snap);
    assertThat(runs.items(id)).hasSize(3);
  }

  @Test
  void disabledProducersAndForwardOrCaseReferencesAreRejectedWithoutRows() throws Exception {
    plan = createPlan(true, List.of(second, first));
    assertThat(admin.call("POST", base + "/runs", input(), UUID.randomUUID().toString()).status())
        .isEqualTo(400);
    ok(
        admin.call(
            "PATCH",
            base + "/plans/" + plan,
            Map.of(
                "version",
                0,
                "steps",
                List.of(
                    Map.of("caseId", first, "enabled", false),
                    Map.of("caseId", second, "enabled", true)))),
        200);
    assertThat(admin.call("POST", base + "/runs", input(), UUID.randomUUID().toString()).status())
        .isEqualTo(400);
    assertThat(
            admin
                .call(
                    "POST",
                    base + "/runs",
                    Map.of("sourceType", "CASE", "sourceId", second, "environmentId", env),
                    UUID.randomUUID().toString())
                .status())
        .isEqualTo(400);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_test_run", Integer.class)).isZero();
  }

  @Test
  void emptyEnabledPlanRejectedAndDisabledItemsExcluded() throws Exception {
    ok(
        admin.call(
            "PATCH",
            base + "/plans/" + plan,
            Map.of("version", 0, "steps", List.of(Map.of("caseId", first, "enabled", false)))),
        200);
    assertThat(admin.call("POST", base + "/runs", input(), UUID.randomUUID().toString()).status())
        .isEqualTo(400);
    ok(
        admin.call(
            "PATCH",
            base + "/plans/" + plan,
            Map.of(
                "version",
                1,
                "steps",
                List.of(
                    Map.of("caseId", first, "enabled", true),
                    Map.of("caseId", second, "enabled", false)))),
        200);
    assertThat(snapshot(submit()).steps()).hasSize(1);
  }

  @Test
  void orderedClaimsRejectOutOfOrderAndFailureSkipsRemainder() throws Exception {
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    assertThat(store.begin(lease, 2)).isFalse();
    assertThat(store.begin(lease, 1)).isTrue();
    assertThat(store.finish(lease, 1, result("FAILED"), null, true)).isTrue();
    assertThat(runs.items(id).stream().map(i -> i.getState()))
        .containsExactly("FAILED", "SKIPPED", "SKIPPED");
    assertThat(runs.find(id).getOutcome()).isEqualTo("FAILED");
    assertThat(store.begin(lease, 2)).isFalse();
  }

  @Test
  void continuingFailureUpdatesProgressAndOnlyFinalizesAfterLastItem() throws Exception {
    plan = createPlan(false, List.of(first, second, second));
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    assertThat(store.begin(lease, 1)).isTrue();
    assertThat(store.finish(lease, 1, result("ERROR"), null, false)).isTrue();
    assertThat(runs.find(id).getState()).isEqualTo("RUNNING");
    assertThat(runs.find(id).getErrorCount()).isEqualTo(1);
    assertThat(store.finish(lease, 1, result("ERROR"), null, false)).isTrue();
    assertThat(runs.find(id).getErrorCount()).isEqualTo(1);
    assertThat(store.begin(lease, 2)).isTrue();
    store.finish(lease, 2, result("ERROR"), null, false);
    assertThat(store.begin(lease, 3)).isTrue();
    store.finish(lease, 3, result("PASSED"), null, false);
    assertThat(runs.find(id).getState()).isEqualTo("COMPLETED");
    assertThat(runs.find(id).getErrorCount()).isEqualTo(2);
  }

  @Test
  void lostWorkerPreservesCommittedStepMarksCurrentUnknownAndRemainderSkipped() throws Exception {
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    store.begin(lease, 1);
    store.finish(lease, 1, result("PASSED"), null, false);
    store.begin(lease, 2);
    jdbc.update(
        "UPDATE tf_test_run SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",
        id);
    store.recover(5);
    assertThat(runs.items(id).stream().map(i -> i.getState()))
        .containsExactly("PASSED", "UNKNOWN", "SKIPPED");
    assertThat(store.finish(lease, 2, result("PASSED"), null, false)).isFalse();
    assertThat(runs.find(id).getOutcome()).isEqualTo("INCOMPLETE");
  }

  @Test
  void cancelPreservesEarlierResultsAndCancelsUndispatchedItems() throws Exception {
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    store.begin(lease, 1);
    store.finish(lease, 1, result("PASSED"), null, false);
    store.cancel(id);
    assertThat(store.begin(lease, 2)).isFalse();
    assertThat(runs.items(id).stream().map(i -> i.getState()))
        .containsExactly("PASSED", "CANCELLED", "CANCELLED");
  }

  @Test
  void rerunCopiesOldInputsAndIdempotencyDoesNotMixOperations() throws Exception {
    long id = submit();
    store.cancel(id);
    ok(
        admin.call("PATCH", base + "/plans/" + plan, Map.of("version", 0, "status", "ARCHIVED")),
        200);
    ok(
        admin.call(
            "PATCH",
            base + "/environments/" + env,
            Map.of("version", 0, "variables", Map.of("marker", "after"), "status", "ARCHIVED")),
        200);
    String key = UUID.randomUUID().toString();
    var firstReply = ok(admin.call("POST", base + "/runs/" + id + "/reruns", Map.of(), key), 202);
    long rerun = Long.parseLong(firstReply.path("id").asString());
    assertThat(runs.find(rerun).getParentRunId()).isEqualTo(id);
    assertThat(snapshot(rerun).variables().get("marker")).isEqualTo("before");
    assertThat(snapshot(rerun).runId()).isEqualTo(rerun);
    assertThat(snapshot(rerun).steps()).isEqualTo(snapshot(id).steps());
    assertThat(
            ok(admin.call("POST", base + "/runs/" + id + "/reruns", Map.of(), key), 200)
                .path("id")
                .asString())
        .isEqualTo(Long.toString(rerun));
    assertThat(admin.call("POST", base + "/runs", input(), key).status()).isEqualTo(409);
    assertThat(
            admin
                .call(
                    "POST",
                    base + "/runs/" + id + "/reruns",
                    Map.of("environmentId", env),
                    UUID.randomUUID().toString())
                .status())
        .isEqualTo(400);
  }

  @Test
  void rerunRefusesActiveRunRevokedPolicyAndUnavailableCiphertext() throws Exception {
    long id = submit();
    assertThat(
            admin
                .call(
                    "POST",
                    base + "/runs/" + id + "/reruns",
                    Map.of(),
                    UUID.randomUUID().toString())
                .status())
        .isEqualTo(409);
    store.cancel(id);
    ok(
        admin.call(
            "PATCH", base + "/target-policies/" + policy, Map.of("version", 0, "enabled", false)),
        200);
    assertThat(
            admin
                .call(
                    "POST",
                    base + "/runs/" + id + "/reruns",
                    Map.of(),
                    UUID.randomUUID().toString())
                .status())
        .isEqualTo(403);
    ok(
        admin.call(
            "PATCH", base + "/target-policies/" + policy, Map.of("version", 1, "enabled", true)),
        200);
    jdbc.update(
        "UPDATE tf_run_snapshot SET encrypted_payload='invalid-synthetic-fixture' WHERE run_id=?",
        id);
    var rejected =
        admin.call(
            "POST", base + "/runs/" + id + "/reruns", Map.of(), UUID.randomUUID().toString());
    assertThat(rejected.status()).isEqualTo(409);
    assertThat(rejected.body().path("error").path("code").asString())
        .isEqualTo("SNAPSHOT_UNAVAILABLE");
  }

  @Test
  void historyUsesSameFiltersForCountAndItemsAndValidatesInputs() throws Exception {
    long id = submit();
    store.cancel(id);
    long other = submit();
    var filtered =
        ok(
            admin.call(
                "GET",
                base
                    + "/runs?sourceType=PLAN&sourceId="
                    + plan
                    + "&environmentId="
                    + env
                    + "&state=CANCELLED&outcome=INCOMPLETE&createdFrom=2020-01-01T00:00:00Z&createdTo=2099-01-01T00:00:00Z&size=1",
                null),
            200);
    assertThat(filtered.path("total").asInt()).isEqualTo(1);
    assertThat(filtered.path("items").get(0).path("id").asString()).isEqualTo(Long.toString(id));
    assertThat(
            ok(admin.call("GET", base + "/runs?sourceType=CASE", null), 200).path("total").asInt())
        .isZero();
    for (String query :
        List.of(
            "sourceType=INDEPENDENT",
            "sourceId=1",
            "state=bogus",
            "createdFrom=bad",
            "createdFrom=2099-01-01T00:00:00Z&createdTo=2020-01-01T00:00:00Z",
            "size=101"))
      assertThat(admin.call("GET", base + "/runs?" + query, null).status()).isEqualTo(400);
    assertThat(
            ok(admin.call("GET", base + "/runs?size=1", null), 200)
                .path("items")
                .get(0)
                .path("id")
                .asString())
        .isEqualTo(Long.toString(other));
  }

  @Test
  void viewerCannotExecuteRerunOrReadAnotherProjectsRun() throws Exception {
    long id = submit();
    store.cancel(id);
    String password = UUID.randomUUID().toString();
    String username = "viewer-" + UUID.randomUUID().toString().substring(0, 8);
    String userId =
        ok(
                admin.call(
                    "POST",
                    "/admin/users",
                    Map.of("username", username, "password", password, "globalAdmin", false)),
                201)
            .path("id")
            .asString();
    ok(admin.call("PUT", base + "/members/" + userId, Map.of("role", "VIEWER")), 200);
    var viewer = new ApiTestSession(port, json);
    viewer.login(username, password);
    assertThat(viewer.call("GET", base + "/runs/" + id + "/report", null).status()).isEqualTo(200);
    assertThat(
            viewer
                .call(
                    "POST",
                    base + "/runs/" + id + "/reruns",
                    Map.of(),
                    UUID.randomUUID().toString())
                .status())
        .isEqualTo(403);
    assertThat(viewer.call("POST", base + "/runs", input(), UUID.randomUUID().toString()).status())
        .isEqualTo(403);
    String owner = ok(admin.call("GET", "/auth/me", null), 200).path("id").asString();
    String other =
        ok(admin.call("POST", "/projects", Map.of("name", "另一项目", "ownerId", owner)), 201)
            .path("id")
            .asString();
    assertThat(
            admin
                .call(
                    "POST",
                    "/projects/" + other + "/runs/" + id + "/reruns",
                    Map.of(),
                    UUID.randomUUID().toString())
                .status())
        .isEqualTo(404);
    ok(admin.call("DELETE", base + "/members/" + userId, null), 200);
    assertThat(viewer.call("GET", base + "/runs?sourceType=PLAN", null).status()).isEqualTo(404);
  }

  @Test
  void concurrentSnapshotRerunsCreateOneChild() throws Exception {
    long id = submit();
    store.cancel(id);
    String key = UUID.randomUUID().toString();
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<ApiTestSession.Reply> call =
          () -> admin.call("POST", base + "/runs/" + id + "/reruns", Map.of(), key);
      var a = pool.submit(call);
      var b = pool.submit(call);
      assertThat(List.of(a.get().status(), b.get().status())).containsExactlyInAnyOrder(202, 200);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM tf_test_run WHERE parent_run_id=?", Integer.class, id))
        .isEqualTo(1);
  }
}
