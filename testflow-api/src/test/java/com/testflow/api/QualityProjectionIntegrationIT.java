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
class QualityProjectionIntegrationIT {
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
        ok(admin.call("POST", "/projects", Map.of("name", "阶段5事务夹具", "ownerId", owner)), 201)
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

  @Autowired com.testflow.persistence.mapper.OutboxMapper events;
  @Autowired com.testflow.persistence.mapper.QualityMapper facts;
  @Autowired com.testflow.api.quality.QualityProjection projection;
  @Autowired com.testflow.api.quality.OutboxStore outbox;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @Autowired com.testflow.persistence.mapper.TargetPolicyMapper targets;
  final com.testflow.core.quality.QualityEventCodec eventCodec =
      new com.testflow.core.quality.QualityEventCodec();

  long completed(String result) throws Exception {
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    for (int n = 1; n <= 4; n++) {
      if (!store.begin(lease, n)) break;
      store.finish(lease, n, result(result), null, true);
    }
    return id;
  }

  void project(long id) {
    var event = events.byRun(id);
    try {
      projection.apply(Long.toString(id), event.payload());
    } catch (IllegalArgumentException error) {
      throw new AssertionError("Rejected synthetic terminal event: " + event.payload(), error);
    }
  }

  @Test
  void everyFinalizationProducesExactlyOneMinimalImmutableEvent() throws Exception {
    long passed = completed("PASSED"), failed = completed("FAILED"), cancelled = submit();
    store.cancel(cancelled);
    long expired = submit();
    jdbc.update(
        "UPDATE tf_test_run SET deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE id=?",
        expired);
    store.recover(5);
    long lost = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    store.begin(lease, 1);
    store.interrupt(lease, "SYNTHETIC_LOSS");
    for (long id : new long[] {passed, failed, cancelled, expired, lost}) {
      var event = events.byRun(id);
      assertThat(event).isNotNull();
      var payload = eventCodec.decode(event.payload());
      assertThat(payload.runId()).isEqualTo(Long.toString(id));
      assertThat(payload.total()).isEqualTo(4);
      assertThat(event.payload())
          .doesNotContain("request", "secret", "variables", "encrypted", "marker");
      store.cancel(id);
      assertThat(events.byRun(id).eventId()).isEqualTo(event.eventId());
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_outbox_event", Integer.class))
        .isEqualTo(5);
  }

  @Test
  void failureToInsertOutboxRollsBackItemResultCountsAndTerminalState() throws Exception {
    plan = independent(false, 2, List.of(first));
    long id = submit();
    var lease = RunStore.lease(store.claim("fixture", 30000));
    store.begin(lease, 1);
    var proxy =
        (RunMapper)
            java.lang.reflect.Proxy.newProxyInstance(
                RunMapper.class.getClassLoader(),
                new Class<?>[] {RunMapper.class},
                (object, method, args) -> {
                  if (method.getName().equals("enqueueFinalized"))
                    throw new IllegalStateException("synthetic outbox failure");
                  try {
                    return method.invoke(runs, args);
                  } catch (java.lang.reflect.InvocationTargetException ex) {
                    throw ex.getCause();
                  }
                });
    var failing = new RunStore(proxy, targets, manager, json);
    assertThatThrownBy(() -> failing.finish(lease, 1, result("PASSED"), null, false))
        .isInstanceOf(IllegalStateException.class);
    assertThat(runs.find(id).getState()).isEqualTo("RUNNING");
    assertThat(runs.items(id).getFirst().getState()).isEqualTo("RUNNING");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_run_item_result", Integer.class))
        .isZero();
    assertThat(events.byRun(id)).isNull();
    store.finish(lease, 1, result("PASSED"), null, false);
    assertThat(events.byRun(id)).isNotNull();
  }

  @Test
  void rollbackAfterEventInsertLeavesBothRunAndOutboxUncommitted() throws Exception {
    long id = submit();
    var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.execute(
        status -> {
          store.cancel(id);
          assertThat(events.byRun(id)).isNotNull();
          status.setRollbackOnly();
          return null;
        });
    assertThat(runs.find(id).getState()).isEqualTo("QUEUED");
    assertThat(events.byRun(id)).isNull();
  }

  @Test
  void competingPublishersAndExpiredTokensCannotLoseOrOverwriteNewOwnership() throws Exception {
    long id = completed("PASSED");
    var claimed = new ArrayList<com.testflow.persistence.mapper.OutboxMapper.Event>();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<com.testflow.persistence.mapper.OutboxMapper.Event> claim =
          () -> {
            gate.await();
            return outbox.claim();
          };
      var a = pool.submit(claim);
      var b = pool.submit(claim);
      gate.countDown();
      if (a.get() != null) claimed.add(a.get());
      if (b.get() != null) claimed.add(b.get());
    }
    assertThat(claimed).hasSize(1);
    var old = claimed.getFirst();
    jdbc.update(
        "UPDATE tf_outbox_event SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE"
            + " run_id=?",
        id);
    var current = outbox.claim();
    assertThat(current.claimToken()).isNotEqualTo(old.claimToken());
    assertThat(outbox.published(old)).isFalse();
    assertThat(outbox.published(current)).isTrue();
    assertThat(events.byRun(id).attemptCount()).isEqualTo(2);
  }

  @Test
  void sendFailureBacksOffWithoutLosingTheDurableEvent() throws Exception {
    long id = completed("PASSED");
    var attempt = outbox.claim();
    outbox.failed(attempt);
    assertThat(outbox.claim()).isNull();
    assertThat(events.byRun(id).status()).isEqualTo("PENDING");
    jdbc.update("UPDATE tf_outbox_event SET next_attempt_at=UTC_TIMESTAMP(3) WHERE run_id=?", id);
    assertThat(outbox.claim().eventId()).isEqualTo(attempt.eventId());
  }

  @Test
  void duplicateAndConcurrentProjectionNeverDoubleCountsFacts() throws Exception {
    long id = completed("PASSED");
    var event = events.byRun(id);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var jobs = new ArrayList<Future<?>>();
      for (int n = 0; n < 4; n++)
        jobs.add(pool.submit(() -> projection.apply(Long.toString(id), event.payload())));
      for (var job : jobs) job.get();
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_quality_run_fact", Integer.class))
        .isEqualTo(1);
    assertThat(facts.fact(id).eventId()).isEqualTo(event.eventId());
    var view = ok(admin.call("GET", base + "/quality", null), 200);
    assertThat(view.at("/metrics/passed").asInt()).isEqualTo(4);
    assertThat(view.at("/status/missingRuns").asInt()).isZero();
  }

  @Test
  void databaseRollbackBeforeConsumerAcknowledgmentRemainsReplayable() throws Exception {
    long id = completed("PASSED");
    var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.execute(
        status -> {
          project(id);
          status.setRollbackOnly();
          return null;
        });
    assertThat(facts.fact(id)).isNull();
    project(id);
    assertThat(facts.fact(id)).isNotNull();
  }

  @Test
  void conflictingPayloadOrExistingFactIsRejectedAndNeverOverwritten() throws Exception {
    long id = completed("PASSED");
    var event = events.byRun(id);
    project(id);
    String tampered = event.payload().replace("\"passed\": 4", "\"passed\": 3");
    assertThatThrownBy(() -> projection.apply("wrong-key", event.payload()))
        .isInstanceOf(IllegalArgumentException.class);
    jdbc.update("UPDATE tf_quality_run_fact SET payload_hash=? WHERE run_id=?", "0".repeat(64), id);
    assertThatThrownBy(() -> project(id)).hasMessage("QUALITY_FACT_CONFLICT");
    assertThat(facts.fact(id).payloadHash()).isEqualTo("0".repeat(64));
    assertThatThrownBy(() -> projection.apply(Long.toString(id), tampered))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void lagAndWeightedRatesDistinguishIncompleteRunsAndZeroDenominators() throws Exception {
    long pass = completed("PASSED"), fail = completed("FAILED"), cancel = submit();
    store.cancel(cancel);
    var empty = ok(admin.call("GET", base + "/quality", null), 200);
    assertThat(empty.at("/status/missingRuns").asInt()).isEqualTo(3);
    assertThat(empty.at("/metrics/passRate").isNull()).isTrue();
    project(pass);
    project(fail);
    project(cancel);
    var regular = ok(admin.call("GET", base + "/quality", null), 200);
    assertThat(regular.at("/metrics/runs").asInt()).isEqualTo(2);
    assertThat(regular.at("/metrics/coverage").asDouble()).isEqualTo(62.5);
    assertThat(regular.at("/metrics/passRate").asDouble()).isEqualTo(80.0);
    assertThat(regular.at("/status/incompleteRuns").asInt()).isEqualTo(1);
    var all = ok(admin.call("GET", base + "/quality?includeIncomplete=true", null), 200);
    assertThat(all.at("/metrics/runs").asInt()).isEqualTo(3);
    assertThat(all.at("/metrics/coverage").asDouble()).isEqualTo(41.67);
    assertThat(all.path("days")).hasSize(1);
  }

  @Test
  void reconcileBackfillsLegacyAndRepublishesMissingFactsWithoutNewRuns() throws Exception {
    long id = completed("PASSED");
    jdbc.update("DELETE FROM tf_outbox_event WHERE run_id=?", id);
    var seeded = ok(admin.call("POST", base + "/quality/reconcile", Map.of("limit", 50)), 200);
    assertThat(seeded.path("createdEvents").asInt()).isEqualTo(1);
    var event = outbox.claim();
    outbox.published(event);
    var replay = ok(admin.call("POST", base + "/quality/reconcile", Map.of("limit", 50)), 200);
    assertThat(replay.path("requeuedEvents").asInt()).isEqualTo(1);
    assertThat(events.byRun(id).status()).isEqualTo("PENDING");
    project(id);
    assertThat(
            ok(admin.call("POST", base + "/quality/reconcile", Map.of("limit", 50)), 200)
                .path("requeuedEvents")
                .asInt())
        .isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_test_run", Integer.class)).isEqualTo(1);
  }

  @Test
  void parkedRecordsAreIdempotentSanitizedAndCanReplayTrustedSources() throws Exception {
    long id = completed("PASSED");
    var event = events.byRun(id);
    projection.park("synthetic", 0, 5, event.payload());
    projection.park("synthetic", 0, 5, event.payload());
    var failures = ok(admin.call("GET", base + "/quality/failures", null), 200);
    assertThat(failures.path("total").asInt()).isEqualTo(1);
    String failure = failures.path("items").get(0).path("id").asString();
    ok(admin.call("POST", base + "/quality/failures/" + failure + "/replay", Map.of()), 200);
    project(id);
    assertThat(
            ok(admin.call("GET", base + "/quality/failures", null), 200)
                .path("items")
                .get(0)
                .path("status")
                .asString())
        .isEqualTo("RESOLVED");
    String canary = "synthetic-private-message-never-log";
    projection.park("synthetic", 0, 6, canary);
    assertThat(ok(admin.call("GET", "/admin/quality/failures/unassigned", null), 200).toString())
        .doesNotContain(canary);
  }

  @Test
  void viewerCanInspectButCannotRepairOrReadUnassignedFailures() throws Exception {
    String username = "quality-" + UUID.randomUUID().toString().substring(0, 12);
    String password = UUID.randomUUID().toString();
    String user =
        ok(
                admin.call(
                    "POST",
                    "/admin/users",
                    Map.of("username", username, "password", password, "globalAdmin", false)),
                201)
            .path("id")
            .asString();
    ok(admin.call("PUT", base + "/members/" + user, Map.of("role", "VIEWER")), 200);
    var viewer = new ApiTestSession(port, json);
    viewer.login(username, password);
    ok(viewer.call("GET", base + "/quality", null), 200);
    ok(viewer.call("GET", base + "/quality/failures", null), 200);
    assertThat(viewer.call("POST", base + "/quality/reconcile", Map.of("limit", 50)).status())
        .isEqualTo(403);
    assertThat(viewer.call("POST", base + "/quality/failures/1/replay", Map.of()).status())
        .isEqualTo(403);
    assertThat(viewer.call("GET", "/admin/quality/failures/unassigned", null).status())
        .isEqualTo(403);
    assertThat(viewer.call("GET", "/projects/9223372036854775807/quality/failures", null).status())
        .isEqualTo(404);
  }

  @Test
  void invalidFiltersAndCrossProjectRepairAreRejected() throws Exception {
    for (String query :
        List.of(
            "sourceId=1",
            "sourceType=BAD",
            "finishedFrom=bad",
            "finishedFrom=2020-01-01T00:00:00Z&finishedTo=2021-01-01T00:00:00Z"))
      assertThat(admin.call("GET", base + "/quality?" + query, null).status()).isEqualTo(400);
    assertThat(admin.call("POST", base + "/quality/reconcile", Map.of("limit", 101)).status())
        .isEqualTo(400);
    assertThat(admin.call("GET", "/projects/9223372036854775807/quality", null).status())
        .isEqualTo(404);
  }
}
