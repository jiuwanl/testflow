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
class IndependentPlanIntegrationIT {
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
        ok(admin.call("POST", "/projects", Map.of("name", "阶段4事务夹具", "ownerId", owner)), 201)
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
    second = createCase("second", config("/second", false, true));
    plan = independent(true, 2, List.of(first, second, second, first));
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

  String independent(boolean fast, int limit, List<String> ids) throws Exception {
    return ok(
            admin.call(
                "POST",
                base + "/plans",
                Map.of(
                    "name",
                    "独立计划",
                    "executionMode",
                    "INDEPENDENT",
                    "maxConcurrency",
                    limit,
                    "failFast",
                    fast,
                    "steps",
                    ids.stream().map(id -> Map.of("caseId", id, "enabled", true)).toList())),
            201)
        .path("id")
        .asString();
  }

  @Test
  void validatesLimitsOnCreateAndSwitch() throws Exception {
    for (int limit : new int[] {0, 5})
      assertThat(
              admin
                  .call(
                      "POST",
                      base + "/plans",
                      Map.of(
                          "name",
                          "bad",
                          "executionMode",
                          "INDEPENDENT",
                          "maxConcurrency",
                          limit,
                          "steps",
                          List.of()))
                  .status())
          .isEqualTo(400);
    assertThat(
            admin
                .call(
                    "PATCH",
                    base + "/plans/" + plan,
                    Map.of("version", 0, "executionMode", "ORDERED", "maxConcurrency", 2))
                .status())
        .isEqualTo(400);
    var ordered =
        ok(
            admin.call(
                "PATCH", base + "/plans/" + plan, Map.of("version", 0, "executionMode", "ORDERED")),
            200);
    assertThat(ordered.path("maxConcurrency").asInt()).isEqualTo(1);
    assertThat(
            ok(
                    admin.call(
                        "PATCH",
                        base + "/plans/" + plan,
                        Map.of("version", 1, "executionMode", "INDEPENDENT")),
                    200)
                .path("maxConcurrency")
                .asInt())
        .isEqualTo(2);
  }

  @Test
  void updatingOnlyFailurePolicyRetainsConcurrencyAndValidatesExistingSteps() throws Exception {
    plan = independent(true, 4, List.of(first, second));
    var updated =
        ok(
            admin.call("PATCH", base + "/plans/" + plan, Map.of("version", 0, "failFast", false)),
            200);
    assertThat(updated.path("maxConcurrency").asInt()).isEqualTo(4);
    assertThat(updated.path("failFast").asBoolean()).isFalse();
  }

  @Test
  void rejectsDependentCasesEvenDisabledAndRejectsModeSwitch() throws Exception {
    String dependent = createCase("dependent", config("/next/${run.orderId}", false, false));
    var steps =
        List.of(
            Map.of("caseId", first, "enabled", true),
            Map.of("caseId", dependent, "enabled", false));
    assertThat(
            admin
                .call(
                    "POST",
                    base + "/plans",
                    Map.of("name", "bad", "executionMode", "INDEPENDENT", "steps", steps))
                .status())
        .isEqualTo(400);
    String ordered = createPlan(true, List.of(first, dependent));
    assertThat(
            admin
                .call(
                    "PATCH",
                    base + "/plans/" + ordered,
                    Map.of("version", 0, "executionMode", "INDEPENDENT"))
                .status())
        .isEqualTo(400);
    assertThat(
            ok(admin.call("GET", base + "/plans/" + ordered, null), 200)
                .path("executionMode")
                .asString())
        .isEqualTo("ORDERED");
  }

  @Test
  void revalidatesEditedCasesAtSubmissionAndLeavesNoRun() throws Exception {
    ok(
        admin.call(
            "PATCH",
            base + "/cases/" + second,
            Map.of("version", 0, "config", config("/changed/${run.orderId}", false, false))),
        200);
    assertThat(admin.call("POST", base + "/runs", input(), UUID.randomUUID().toString()).status())
        .isEqualTo(400);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_test_run", Integer.class)).isZero();
  }

  @Test
  void freezesModeAndLimitAndRerunsOldPolicy() throws Exception {
    long id = submit();
    var frozen = snapshot(id);
    assertThat(frozen.executionMode()).isEqualTo("INDEPENDENT");
    assertThat(frozen.maxConcurrency()).isEqualTo(2);
    assertThat(frozen.schemaVersion()).isEqualTo(3);
    ok(
        admin.call(
            "PATCH", base + "/plans/" + plan, Map.of("version", 0, "executionMode", "ORDERED")),
        200);
    store.cancel(id);
    long child =
        Long.parseLong(
            ok(
                    admin.call(
                        "POST",
                        base + "/runs/" + id + "/reruns",
                        Map.of(),
                        UUID.randomUUID().toString()),
                    202)
                .path("id")
                .asString());
    assertThat(snapshot(child).executionMode()).isEqualTo("INDEPENDENT");
    assertThat(runs.find(child).getMaxConcurrency()).isEqualTo(2);
    assertThat(snapshot(child).steps()).isEqualTo(frozen.steps());
    assertThat(runs.find(submit()).getExecutionMode()).isEqualTo("ORDERED");
  }

  @Test
  void competingBeginsEnforceFrozenBudgetAndAllowOutOfOrder() throws Exception {
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    try (var pool = Executors.newFixedThreadPool(4)) {
      var gate = new CountDownLatch(1);
      var jobs = new ArrayList<Future<Boolean>>();
      for (int n = 1; n <= 4; n++) {
        int ordinal = n;
        jobs.add(
            pool.submit(
                () -> {
                  gate.await();
                  return store.begin(lease, ordinal);
                }));
      }
      gate.countDown();
      int accepted = 0;
      for (var job : jobs) if (job.get()) accepted++;
      assertThat(accepted).isEqualTo(2);
    }
    assertThat(runs.items(id).stream().filter(i -> i.getState().equals("RUNNING"))).hasSize(2);
    store.cancel(id);
  }

  @Test
  void parallelCompletionsAreCountedExactlyOnce() throws Exception {
    plan = independent(false, 4, List.of(first, second, second, first));
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    for (int ordinal : new int[] {4, 2, 3, 1}) assertThat(store.begin(lease, ordinal)).isTrue();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var gate = new CountDownLatch(1);
      var jobs = new ArrayList<Future<Boolean>>();
      for (int ordinal = 1; ordinal <= 4; ordinal++) {
        int n = ordinal;
        jobs.add(
            pool.submit(
                () -> {
                  gate.await();
                  return store.finish(lease, n, result(n == 2 ? "FAILED" : "PASSED"), null, false);
                }));
      }
      gate.countDown();
      for (var job : jobs) assertThat(job.get()).isTrue();
    }
    assertThat(runs.find(id).getState()).isEqualTo("COMPLETED");
    assertThat(runs.find(id).getPassedCount()).isEqualTo(3);
    assertThat(runs.find(id).getFailedCount()).isEqualTo(1);
    assertThat(store.finish(lease, 2, result("PASSED"), null, false)).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_run_item_result", Integer.class))
        .isEqualTo(4);
  }

  @Test
  void reportReadsCountsAndItemsFromOneSnapshotDuringConcurrentWrites() throws Exception {
    plan = independent(false, 4, java.util.Collections.nCopies(32, first));
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    try (var pool = Executors.newSingleThreadExecutor()) {
      var start = new CountDownLatch(1);
      var writer =
          pool.submit(
              () -> {
                start.await();
                for (int n = 1; n <= 32; n++) {
                  assertThat(store.begin(lease, n)).isTrue();
                  store.finish(lease, n, result("PASSED"), null, false);
                  Thread.sleep(5);
                }
                return true;
              });
      start.countDown();
      int reads = 0;
      do {
        var report = ok(admin.call("GET", base + "/runs/" + id + "/report", null), 200);
        int passed = 0;
        for (var item : report.path("items"))
          if (item.path("state").asString().equals("PASSED")) passed++;
        assertThat(report.at("/run/metrics/passed").asInt()).isEqualTo(passed);
        reads++;
      } while (!writer.isDone());
      assertThat(writer.get()).isTrue();
      assertThat(reads).isPositive();
    }
  }

  @Test
  void failFastKeepsInflightResultsAndDoesNotFinalizeEarly() throws Exception {
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    store.begin(lease, 2);
    store.begin(lease, 1);
    store.finish(lease, 2, result("FAILED"), null, true);
    assertThat(runs.find(id).getState()).isEqualTo("RUNNING");
    assertThat(runs.find(id).getTerminalReason()).isEqualTo("FAIL_FAST");
    assertThat(runs.items(id).stream().map(i -> i.getState()))
        .containsExactly("RUNNING", "FAILED", "SKIPPED", "SKIPPED");
    assertThat(store.begin(lease, 3)).isFalse();
    store.finish(lease, 1, result("PASSED"), null, true);
    assertThat(runs.find(id).getState()).isEqualTo("COMPLETED");
    assertThat(runs.find(id).getOutcome()).isEqualTo("FAILED");
    assertThat(runs.find(id).getPassedCount()).isEqualTo(1);
  }

  @Test
  void cancellationWaitsForAllInflightAndPreservesKnownResults() throws Exception {
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    store.begin(lease, 1);
    store.begin(lease, 2);
    assertThat(store.cancel(id).getState()).isEqualTo("CANCELLING");
    assertThat(store.begin(lease, 3)).isFalse();
    store.finish(lease, 2, result("PASSED"), null, false);
    assertThat(runs.find(id).getState()).isEqualTo("CANCELLING");
    store.finish(lease, 1, ExecutionResult.error("USER_CANCELLED", 1), null, false);
    assertThat(runs.find(id).getState()).isEqualTo("CANCELLED");
    assertThat(runs.items(id).stream().map(i -> i.getState()))
        .containsExactly("ERROR", "PASSED", "CANCELLED", "CANCELLED");
  }

  @Test
  void cancellationAndLastResultRaceHasOneConsistentFinalization() throws Exception {
    plan = independent(false, 2, List.of(first, second));
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    store.begin(lease, 1);
    store.begin(lease, 2);
    store.finish(lease, 1, result("PASSED"), null, false);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                gate.await();
                store.cancel(id);
                return true;
              });
      var b =
          pool.submit(
              () -> {
                gate.await();
                return store.finish(lease, 2, result("PASSED"), null, false);
              });
      gate.countDown();
      a.get();
      b.get();
    }
    assertThat(runs.find(id).getState()).isIn("COMPLETED", "CANCELLED");
    assertThat(runs.find(id).getPassedCount()).isEqualTo(2);
    assertThat(runs.items(id).stream().map(i -> i.getState())).containsOnly("PASSED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_run_item_result", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void lostLeaseMarksEveryInflightUnknownAndFencesLateResults() throws Exception {
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    store.begin(lease, 1);
    store.begin(lease, 2);
    jdbc.update(
        "UPDATE tf_test_run SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",
        id);
    store.recover(5);
    assertThat(runs.items(id).stream().map(i -> i.getState()))
        .containsExactly("UNKNOWN", "UNKNOWN", "SKIPPED", "SKIPPED");
    assertThat(store.finish(lease, 1, result("PASSED"), null, true)).isFalse();
    assertThat(store.begin(lease, 3)).isFalse();
    assertThat(runs.find(id).getUnknownCount()).isEqualTo(2);
    assertThat(store.claim("replacement", 30000)).isNull();
  }

  @Test
  void deadlineTerminatesAllInflightAndPreventsLateWrites() throws Exception {
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    store.begin(lease, 1);
    store.begin(lease, 2);
    jdbc.update(
        "UPDATE tf_test_run SET deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",
        id);
    store.recover(5);
    assertThat(runs.find(id).getState()).isEqualTo("TIMED_OUT");
    assertThat(runs.find(id).getUnknownCount()).isEqualTo(2);
    assertThat(runs.find(id).getSkippedCount()).isEqualTo(2);
    assertThat(store.finish(lease, 2, result("PASSED"), null, false)).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_run_item_result", Integer.class))
        .isZero();
  }

  @Test
  void ownerAndVersionChangesFenceAllStepWriters() throws Exception {
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    store.begin(lease, 1);
    store.begin(lease, 2);
    jdbc.update(
        "UPDATE tf_test_run SET lease_version=lease_version+1,owner_id='replacement' WHERE id=?",
        id);
    assertThat(store.begin(lease, 3)).isFalse();
    assertThat(store.finish(lease, 1, result("PASSED"), null, false)).isFalse();
    assertThat(store.pulse(lease, 30000)).isEqualTo(RunStore.Signal.LOST);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_run_item_result", Integer.class))
        .isZero();
  }
}
