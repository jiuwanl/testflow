package com.testflow.api;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ManagementIntegrationIT {
  static {
    String url = System.getenv("TF_DB_URL");
    if (url == null || !url.contains("/testflow_test?")) {
      throw new IllegalStateException(
          "Integration tests require the dedicated testflow_test database");
    }
  }

  @Value("${local.server.port}")
  int port;

  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  private Session admin;
  private String adminId, projectId, maintainerId, viewerId, fixturePassword;
  private final String adminUsername = System.getenv("TF_BOOTSTRAP_ADMIN_USERNAME");
  private final String adminPassword = System.getenv("TF_BOOTSTRAP_ADMIN_PASSWORD");

  @BeforeEach
  void fixtures() throws Exception {
    try (var cursor =
        redis.scan(ScanOptions.scanOptions().match("testflow-test:login:*").count(100).build())) {
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
    jdbc.update("DELETE FROM tf_user WHERE username<>?", adminUsername);
    fixturePassword = UUID.randomUUID().toString();
    admin = new Session();
    admin.login(adminUsername, adminPassword);
    adminId = admin.call("GET", "/auth/me", null).data().path("id").asString();
    maintainerId = createUser("maintainer");
    viewerId = createUser("viewer");
    projectId = createProject("合同测试项目", adminId);
    addMember(projectId, maintainerId, "MAINTAINER");
    addMember(projectId, viewerId, "VIEWER");
  }

  private String createUser(String name) throws Exception {
    return accepted(
            admin.call(
                "POST",
                "/admin/users",
                Map.of("username", name, "password", fixturePassword, "globalAdmin", false)),
            201)
        .path("id")
        .asString();
  }

  private String createProject(String name, String ownerId) throws Exception {
    return accepted(admin.call("POST", "/projects", Map.of("name", name, "ownerId", ownerId)), 201)
        .path("id")
        .asString();
  }

  private void addMember(String project, String user, String role) throws Exception {
    accepted(
        admin.call("PUT", "/projects/" + project + "/members/" + user, Map.of("role", role)), 200);
  }

  private String base() {
    return "/projects/" + projectId;
  }

  private Map<String, Object> caseInput(String name) {
    return Map.of(
        "name",
        name,
        "tags",
        List.of("回归"),
        "priority",
        "P1",
        "configSchemaVersion",
        1,
        "config",
        Map.of(
            "request",
            Map.of(
                "method",
                "GET",
                "path",
                "/items",
                "query",
                List.of(),
                "headers",
                List.of(),
                "body",
                Map.of("type", "NONE")),
            "assertions",
            List.of(Map.of("id", "status", "type", "STATUS_CODE", "expected", 200)),
            "extractors",
            List.of(),
            "timeoutMs",
            15000));
  }

  private String createCase() throws Exception {
    return accepted(admin.call("POST", base() + "/cases", caseInput("接口回归")), 201)
        .path("id")
        .asString();
  }

  private JsonNode accepted(Reply reply, int status) {
    assertThat(reply.status()).isEqualTo(status);
    return reply.data();
  }

  @Test
  void cookieCsrfRotationLogoutAndAnonymousAccess() throws Exception {
    Session session = new Session();
    assertThat(session.call("GET", "/projects", null).status()).isEqualTo(401);
    assertThat(
            session
                .raw(
                    "POST",
                    "/auth/login",
                    Map.of("username", adminUsername, "password", adminPassword),
                    false)
                .status())
        .isEqualTo(403);
    session.csrf();
    String oldCookie = session.cookie(), oldToken = session.token;
    accepted(
        session.call(
            "POST", "/auth/login", Map.of("username", adminUsername, "password", adminPassword)),
        200);
    assertThat(session.cookie().equals(oldCookie)).isFalse();
    session.token = oldToken;
    assertThat(
            session.call("POST", "/projects", Map.of("name", "过期令牌", "ownerId", adminId)).status())
        .isEqualTo(403);
    session.token = null;
    accepted(session.call("GET", "/auth/me", null), 200);
    accepted(session.call("POST", "/auth/logout", Map.of()), 200);
    assertThat(session.call("GET", "/projects", null).status()).isEqualTo(401);
  }

  @Test
  void viewerCanReadButCannotWriteAnyProjectDefinitionOrAdminAccount() throws Exception {
    String caseId = createCase();
    String environment =
        accepted(
                admin.call(
                    "POST",
                    base() + "/environments",
                    Map.of(
                        "name", "测试环境", "baseUrl", "http://127.0.0.1:8088", "variables", Map.of())),
                201)
            .path("id")
            .asString();
    Session viewer = new Session();
    viewer.login("viewer", fixturePassword);
    accepted(viewer.call("GET", base() + "/cases/" + caseId, null), 200);
    accepted(viewer.call("GET", base() + "/environments/" + environment, null), 200);
    assertThat(viewer.call("POST", base() + "/cases", caseInput("越权创建")).status()).isEqualTo(403);
    assertThat(
            viewer
                .call("PATCH", base() + "/cases/" + caseId, Map.of("version", 0, "name", "越权修改"))
                .status())
        .isEqualTo(403);
    assertThat(
            viewer
                .call(
                    "POST",
                    base() + "/environments",
                    Map.of("name", "越权", "baseUrl", "http://example.com", "variables", Map.of()))
                .status())
        .isEqualTo(403);
    assertThat(
            viewer
                .call(
                    "POST",
                    base() + "/plans",
                    Map.of(
                        "name",
                        "越权",
                        "executionMode",
                        "ORDERED",
                        "failFast",
                        true,
                        "steps",
                        List.of()))
                .status())
        .isEqualTo(403);
    assertThat(viewer.call("GET", "/admin/users", null).status()).isEqualTo(403);
    assertThat(viewer.call("GET", base() + "/members", null).status()).isEqualTo(403);
  }

  @Test
  void projectAndObjectIdsCannotCrossAuthorizationBoundary() throws Exception {
    String foreign = createProject("隔离项目", adminId);
    String foreignCase =
        accepted(admin.call("POST", "/projects/" + foreign + "/cases", caseInput("隔离用例")), 201)
            .path("id")
            .asString();
    Session viewer = new Session();
    viewer.login("viewer", fixturePassword);
    assertThat(viewer.call("GET", "/projects/" + foreign + "/cases", null).status()).isEqualTo(404);
    assertThat(viewer.call("GET", base() + "/cases/" + foreignCase, null).status()).isEqualTo(404);
    assertThat(
            admin
                .call(
                    "POST",
                    base() + "/plans",
                    Map.of(
                        "name",
                        "非法引用",
                        "executionMode",
                        "ORDERED",
                        "failFast",
                        true,
                        "steps",
                        List.of(Map.of("caseId", foreignCase, "enabled", true))))
                .status())
        .isEqualTo(404);
    assertThat(admin.call("GET", base() + "/plans", null).data().path("total").asLong()).isZero();
    String notMine = createProject("管理员不自动入项目", maintainerId);
    assertThat(admin.call("GET", "/projects/" + notMine, null).status()).isEqualTo(404);
  }

  @Test
  void secretsAreEncryptedAndNeverReturnedAndStaleEditsFail() throws Exception {
    String secret = UUID.randomUUID().toString();
    Reply created =
        admin.call(
            "POST",
            base() + "/environments",
            Map.of(
                "name",
                "加密环境",
                "baseUrl",
                "http://127.0.0.1:8088",
                "variables",
                Map.of("region", "local"),
                "secretUpdates",
                List.of(Map.of("name", "token", "operation", "SET", "value", secret))));
    JsonNode env = accepted(created, 201);
    String id = env.path("id").asString();
    assertThat(created.body().toString().contains(secret)).isFalse();
    assertThat(env.path("secretNames").get(0).asString()).isEqualTo("token");
    String cipher =
        jdbc.queryForObject(
            "SELECT secret_ciphertext FROM tf_environment WHERE id=?", String.class, id);
    assertThat(cipher != null && cipher.contains(secret)).isFalse();
    accepted(
        admin.call("PATCH", base() + "/environments/" + id, Map.of("version", 0, "name", "已更新")),
        200);
    assertThat(
            admin
                .call("PATCH", base() + "/environments/" + id, Map.of("version", 0, "name", "旧编辑"))
                .status())
        .isEqualTo(409);
    assertThat(
            admin
                .call(
                    "PATCH",
                    base() + "/environments/" + id,
                    Map.of("version", 1, "variables", Map.of("token", "plaintext")))
                .status())
        .isEqualTo(400);
    JsonNode unchanged = accepted(admin.call("GET", base() + "/environments/" + id, null), 200);
    assertThat(unchanged.path("version").asLong()).isEqualTo(1);
    JsonNode removed =
        accepted(
            admin.call(
                "PATCH",
                base() + "/environments/" + id,
                Map.of(
                    "version",
                    1,
                    "secretUpdates",
                    List.of(Map.of("name", "token", "operation", "REMOVE")))),
            200);
    assertThat(removed.path("secretNames").size()).isZero();
  }

  @Test
  void planReplacementIsAtomicAndDuplicateCaseReferencesAreIndependentSteps() throws Exception {
    String caseId = createCase();
    JsonNode plan =
        accepted(
            admin.call(
                "POST",
                base() + "/plans",
                Map.of(
                    "name",
                    "有序计划",
                    "executionMode",
                    "ORDERED",
                    "failFast",
                    true,
                    "steps",
                    List.of(
                        Map.of("caseId", caseId, "enabled", true),
                        Map.of("caseId", caseId, "enabled", false)))),
            201);
    String id = plan.path("id").asString();
    assertThat(plan.path("steps").size()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(DISTINCT id) FROM tf_plan_step WHERE plan_id=?", Integer.class, id))
        .isEqualTo(2);
    assertThat(
            admin
                .call(
                    "PATCH",
                    base() + "/plans/" + id,
                    Map.of(
                        "version",
                        0,
                        "steps",
                        List.of(Map.of("caseId", "9223372036854775807", "enabled", true))))
                .status())
        .isEqualTo(404);
    JsonNode unchanged = accepted(admin.call("GET", base() + "/plans/" + id, null), 200);
    assertThat(unchanged.path("steps").size()).isEqualTo(2);
    assertThat(unchanged.path("version").asLong()).isZero();
    assertThat(
            admin
                .call(
                    "POST",
                    base() + "/plans",
                    Map.of(
                        "name",
                        "非法并发模式",
                        "executionMode",
                        "PARALLEL_UNSUPPORTED",
                        "failFast",
                        true,
                        "steps",
                        List.of()))
                .status())
        .isEqualTo(400);
  }

  @Test
  void lastOwnerCannotBeRemovedAndSelfDemotionTakesEffectImmediately() throws Exception {
    assertThat(admin.call("DELETE", base() + "/members/" + adminId, null).status()).isEqualTo(409);
    accepted(
        admin.call(
            "PUT", base() + "/members/" + maintainerId, Map.of("role", "OWNER", "version", 0)),
        200);
    accepted(
        admin.call("PUT", base() + "/members/" + adminId, Map.of("role", "VIEWER", "version", 0)),
        200);
    assertThat(admin.call("POST", base() + "/cases", caseInput("降权后写入")).status()).isEqualTo(403);
    accepted(admin.call("GET", base() + "/cases", null), 200);
  }

  @Test
  void disablingAccountInvalidatesExistingSessionsAndDoesNotLeakPasswordHash() throws Exception {
    Session maintainer = new Session();
    maintainer.login("maintainer", fixturePassword);
    Reply changed =
        admin.call(
            "PATCH", "/admin/users/" + maintainerId, Map.of("version", 0, "status", "DISABLED"));
    accepted(changed, 200);
    assertThat(changed.data().has("passwordHash")).isFalse();
    assertThat(maintainer.call("GET", "/auth/me", null).status()).isEqualTo(401);
    assertThat(maintainer.call("GET", base() + "/cases", null).status()).isEqualTo(401);
    assertThat(
            admin
                .call(
                    "PATCH", "/admin/users/" + adminId, Map.of("version", 0, "status", "DISABLED"))
                .status())
        .isEqualTo(409);
  }

  @Test
  void loginBruteForceUsesRedisAndReturnsRetryAfter() throws Exception {
    Session attacker = new Session();
    for (int i = 0; i < 8; i++)
      assertThat(
              attacker
                  .call(
                      "POST",
                      "/auth/login",
                      Map.of("username", "unknown", "password", fixturePassword))
                  .status())
          .isEqualTo(401);
    Reply blocked =
        attacker.call(
            "POST", "/auth/login", Map.of("username", "unknown", "password", fixturePassword));
    assertThat(blocked.status()).isEqualTo(429);
    assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
  }

  @Test
  void runtimeCredentialsCannotExecuteDdlAndSchemaWasMigratedOnce() throws Exception {
    assertThatThrownBy(() -> jdbc.execute("CREATE TABLE tf_forbidden_ddl(id INT)"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    String workerPassword = System.getenv("TF_TEST_WORKER_PASSWORD");
    try (var connection =
            DriverManager.getConnection(
                System.getenv("TF_DB_URL"), "tf_test_worker", workerPassword);
        var statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("CREATE TABLE tf_worker_forbidden_ddl(id INT)"))
          .isInstanceOf(java.sql.SQLException.class);
      try (var result =
          statement.executeQuery(
              "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY"
                  + " installed_rank")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("1");
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("2");
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("3");
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("4");
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("5");
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo(com.testflow.persistence.SchemaVersion.EXPECTED);
        assertThat(result.next()).isFalse();
      }
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND"
                    + " table_name='tf_test_run'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void inputBoundariesAndSqlParametersAndPhaseTwoRoutes() throws Exception {
    createCase();
    assertThat(admin.call("GET", base() + "/cases?size=101", null).status()).isEqualTo(400);
    assertThat(admin.call("GET", base() + "/cases/not-a-number", null).status()).isEqualTo(400);
    assertThat(admin.call("DELETE", base() + "/cases/1", null).status()).isEqualTo(405);
    String injection = URLEncoder.encode("' OR 1=1 --", StandardCharsets.UTF_8);
    assertThat(
            admin
                .call("GET", base() + "/cases?name=" + injection, null)
                .data()
                .path("total")
                .asLong())
        .isZero();
    assertThat(admin.call("POST", base() + "/runs", Map.of()).status()).isEqualTo(400);
    assertThat(
            admin
                .call(
                    "POST",
                    base() + "/environments",
                    Map.of("name", "bad", "baseUrl", "file:///etc/passwd", "variables", Map.of()))
                .status())
        .isEqualTo(400);
    Reply invalid =
        admin.call(
            "POST",
            base() + "/cases",
            Map.of("name", "bad", "configSchemaVersion", 1, "config", Map.of()));
    assertThat(invalid.status()).isEqualTo(400);
    assertThat(invalid.body().toString().contains("SQLException")).isFalse();
    assertThat(invalid.body().path("requestId").asString()).isNotBlank();
  }

  @Test
  void archivedProjectIsReadableButNotWritableAndPoliciesRequireAdministrator() throws Exception {
    Session maintainer = new Session();
    maintainer.login("maintainer", fixturePassword);
    assertThat(
            maintainer
                .call(
                    "POST",
                    base() + "/target-policies",
                    Map.of(
                        "scheme",
                        "https",
                        "host",
                        "example.com",
                        "port",
                        443,
                        "addressScope",
                        "PUBLIC"))
                .status())
        .isEqualTo(403);
    accepted(
        admin.call(
            "POST",
            base() + "/target-policies",
            Map.of(
                "scheme", "https", "host", "EXAMPLE.COM.", "port", 443, "addressScope", "PUBLIC")),
        201);
    assertThat(
            admin
                .call(
                    "POST",
                    base() + "/target-policies",
                    Map.of(
                        "scheme",
                        "http",
                        "host",
                        "169.254.169.254",
                        "port",
                        80,
                        "addressScope",
                        "PRIVATE"))
                .status())
        .isEqualTo(400);
    accepted(
        admin.call(
            "PATCH", base(), Map.of("version", projectVersion(projectId), "status", "ARCHIVED")),
        200);
    accepted(maintainer.call("GET", base() + "/cases", null), 200);
    assertThat(maintainer.call("POST", base() + "/cases", caseInput("归档写入")).status())
        .isEqualTo(409);
  }

  @Test
  void requestLimitsAlsoCoverChunkedBodiesAndRejectCiphertextInjection() throws Exception {
    String oversized =
        json.writeValueAsString(
            Map.of(
                "name",
                "x".repeat(1024 * 1024 + 64),
                "baseUrl",
                "http://127.0.0.1:8088",
                "variables",
                Map.of()));
    assertThat(admin.call("POST", base() + "/environments", json.readTree(oversized)).status())
        .isEqualTo(413);
    assertThat(admin.chunked(base() + "/environments", oversized).status()).isEqualTo(413);
    assertThat(
            admin
                .call(
                    "POST",
                    base() + "/environments",
                    Map.of(
                        "name",
                        "不允许密文注入",
                        "baseUrl",
                        "http://127.0.0.1:8088",
                        "variables",
                        Map.of(),
                        "secretCiphertext",
                        "injected"))
                .status())
        .isEqualTo(400);
  }

  @Test
  void concurrentOwnerRemovalPreservesAnActiveOwner() throws Exception {
    accepted(
        admin.call(
            "PUT", base() + "/members/" + maintainerId, Map.of("role", "OWNER", "version", 0)),
        200);
    Session otherOwner = new Session();
    otherOwner.login("maintainer", fixturePassword);
    admin.csrf();
    otherOwner.csrf();
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return admin.call("DELETE", base() + "/members/" + adminId, null).status();
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return otherOwner
                    .call("DELETE", base() + "/members/" + maintainerId, null)
                    .status();
              });
      start.countDown();
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM tf_project_member m JOIN tf_user u ON u.id=m.user_id WHERE"
                    + " m.project_id=? AND m.role='OWNER' AND u.status='ACTIVE'",
                Integer.class,
                projectId))
        .isEqualTo(1);
  }

  private long projectVersion(String project) {
    return jdbc.queryForObject("SELECT version FROM tf_project WHERE id=?", Long.class, project);
  }

  private int ownerCount(String project) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM tf_project_member m JOIN tf_user u ON u.id=m.user_id WHERE"
            + " m.project_id=? AND m.role='OWNER' AND u.status='ACTIVE'",
        Integer.class,
        project);
  }

  private Map<String, Object> transfer(String project, String successor) {
    return Map.of(
        "projectId", project, "version", projectVersion(project), "successorUserId", successor);
  }

  private Map<String, Object> disable(String user, List<?> transfers) {
    return Map.of(
        "version",
        jdbc.queryForObject("SELECT version FROM tf_user WHERE id=?", Long.class, user),
        "status",
        "DISABLED",
        "ownershipTransfers",
        transfers);
  }

  @Test
  void twoOwnersSequentialDemotionKeepsOneEffectiveOwner() throws Exception {
    accepted(
        admin.call(
            "PUT", base() + "/members/" + maintainerId, Map.of("role", "OWNER", "version", 0)),
        200);
    Session second = new Session();
    second.login("maintainer", fixturePassword);
    accepted(
        admin.call("PUT", base() + "/members/" + adminId, Map.of("role", "VIEWER", "version", 0)),
        200);
    var denied =
        second.call(
            "PUT", base() + "/members/" + maintainerId, Map.of("role", "VIEWER", "version", 1));
    assertThat(denied.status()).isEqualTo(409);
    assertThat(denied.body().at("/error/code").asString()).isEqualTo("LAST_OWNER");
    assertThat(ownerCount(projectId)).isEqualTo(1);
  }

  private List<Integer> race(
      java.util.concurrent.Callable<Integer> a, java.util.concurrent.Callable<Integer> b)
      throws Exception {
    var gate = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () -> {
                gate.await();
                return a.call();
              });
      var second =
          pool.submit(
              () -> {
                gate.await();
                return b.call();
              });
      gate.countDown();
      return List.of(
          first.get(20, java.util.concurrent.TimeUnit.SECONDS),
          second.get(20, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  @Test
  void simultaneousSelfDemotionsSerializeAndKeepOwner() throws Exception {
    accepted(
        admin.call(
            "PUT", base() + "/members/" + maintainerId, Map.of("role", "OWNER", "version", 0)),
        200);
    Session second = new Session();
    second.login("maintainer", fixturePassword);
    second.csrf();
    admin.csrf();
    assertThat(
            race(
                () ->
                    admin
                        .call(
                            "PUT",
                            base() + "/members/" + adminId,
                            Map.of("role", "VIEWER", "version", 0))
                        .status(),
                () ->
                    second
                        .call(
                            "PUT",
                            base() + "/members/" + maintainerId,
                            Map.of("role", "MAINTAINER", "version", 1))
                        .status()))
        .containsExactlyInAnyOrder(200, 409);
    assertThat(ownerCount(projectId)).isEqualTo(1);
  }

  @Test
  void simultaneousDemotionAndRemovalKeepOwner() throws Exception {
    accepted(
        admin.call(
            "PUT", base() + "/members/" + maintainerId, Map.of("role", "OWNER", "version", 0)),
        200);
    Session second = new Session();
    second.login("maintainer", fixturePassword);
    second.csrf();
    admin.csrf();
    assertThat(
            race(
                () ->
                    admin
                        .call(
                            "PUT",
                            base() + "/members/" + adminId,
                            Map.of("role", "VIEWER", "version", 0))
                        .status(),
                () -> second.call("DELETE", base() + "/members/" + maintainerId, null).status()))
        .containsExactlyInAnyOrder(200, 409);
    assertThat(ownerCount(projectId)).isEqualTo(1);
  }

  @Test
  void nonMemberAdminCanTransferButCannotReadProjectBusiness() throws Exception {
    String foreign = createProject("交接项目", maintainerId);
    assertThat(admin.call("GET", "/projects/" + foreign, null).status()).isEqualTo(404);
    assertThat(admin.call("GET", "/projects/" + foreign + "/members", null).status())
        .isEqualTo(404);
    var detail =
        accepted(admin.call("GET", "/admin/projects/" + foreign + "/ownership", null), 200);
    assertThat(detail.path("project").has("config")).isFalse();
    accepted(
        admin.call(
            "PUT",
            "/admin/projects/" + foreign + "/ownership",
            Map.of(
                "version",
                projectVersion(foreign),
                "fromUserId",
                maintainerId,
                "successorUserId",
                viewerId,
                "reason",
                "离职交接")),
        200);
    assertThat(
            jdbc.queryForObject(
                "SELECT role FROM tf_project_member WHERE project_id=? AND user_id=?",
                String.class,
                foreign,
                viewerId))
        .isEqualTo("OWNER");
    assertThat(
            jdbc.queryForObject(
                "SELECT role FROM tf_project_member WHERE project_id=? AND user_id=?",
                String.class,
                foreign,
                maintainerId))
        .isEqualTo("MAINTAINER");
    for (String resource : List.of("", "/environments", "/cases", "/plans", "/runs"))
      assertThat(admin.call("GET", "/projects/" + foreign + resource, null).status())
          .isEqualTo(404);
    String summary =
        jdbc.queryForObject(
            "SELECT summary_json FROM tf_audit_event WHERE project_id=? AND"
                + " action='OWNERSHIP_TRANSFER'",
            String.class,
            foreign);
    assertThat(json.readTree(summary).path("reason").asString()).isEqualTo("离职交接");
  }

  @Test
  void coOwnerCanBeDisabledWithoutTransferAndOldSessionIsInvalid() throws Exception {
    accepted(
        admin.call(
            "PUT", base() + "/members/" + maintainerId, Map.of("role", "OWNER", "version", 0)),
        200);
    Session second = new Session();
    second.login("maintainer", fixturePassword);
    var preview =
        accepted(admin.call("GET", "/admin/users/" + maintainerId + "/disable-impact", null), 200);
    assertThat(preview.at("/projects/0/requiresTransfer").asBoolean()).isFalse();
    accepted(
        admin.call("PATCH", "/admin/users/" + maintainerId, disable(maintainerId, List.of())), 200);
    assertThat(ownerCount(projectId)).isEqualTo(1);
    assertThat(second.call("GET", "/auth/me", null).status()).isEqualTo(401);
    assertThat(second.call("GET", base() + "/cases", null).status()).isEqualTo(401);
    accepted(
        admin.call(
            "PATCH", "/admin/users/" + maintainerId, Map.of("version", 1, "status", "ACTIVE")),
        200);
    assertThat(ownerCount(projectId)).isEqualTo(2);
    assertThat(second.call("GET", "/auth/me", null).status()).isEqualTo(401);
  }

  @Test
  void soleOwnerDisableReturnsProjectsAndRequiresAtomicHandover() throws Exception {
    String foreign = createProject("唯一所有者", maintainerId);
    var denied =
        admin.call("PATCH", "/admin/users/" + maintainerId, disable(maintainerId, List.of()));
    assertThat(denied.status()).isEqualTo(409);
    assertThat(denied.body().at("/error/code").asString()).isEqualTo("OWNER_TRANSFER_REQUIRED");
    assertThat(denied.body().at("/error/fieldErrors/0/id").asString()).isEqualTo(foreign);
    accepted(
        admin.call(
            "PATCH",
            "/admin/users/" + maintainerId,
            disable(maintainerId, List.of(transfer(foreign, viewerId)))),
        200);
    assertThat(ownerCount(foreign)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT role FROM tf_project_member WHERE project_id=? AND user_id=?",
                String.class,
                foreign,
                maintainerId))
        .isEqualTo("MAINTAINER");
  }

  @Test
  void multipleProjectHandoverRollsBackAllWritesAndAuditsOnLaterFailure() throws Exception {
    String a = createProject("交接甲", maintainerId), b = createProject("交接乙", maintainerId);
    accepted(admin.call("PATCH", "/admin/users/" + viewerId, disable(viewerId, List.of())), 200);
    int before = jdbc.queryForObject("SELECT COUNT(*) FROM tf_audit_event", Integer.class);
    var result =
        admin.call(
            "PATCH",
            "/admin/users/" + maintainerId,
            disable(maintainerId, List.of(transfer(a, adminId), transfer(b, viewerId))));
    assertThat(result.status()).isEqualTo(400);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_audit_event", Integer.class))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM tf_user WHERE id=?", String.class, maintainerId))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM tf_project_member WHERE project_id=? AND user_id=?",
                Integer.class,
                a,
                adminId))
        .isZero();
    assertThat(ownerCount(a)).isEqualTo(1);
    assertThat(ownerCount(b)).isEqualTo(1);
    assertThat(projectVersion(a)).isZero();
    accepted(
        admin.call(
            "PATCH",
            "/admin/users/" + maintainerId,
            disable(maintainerId, List.of(transfer(a, adminId), transfer(b, adminId)))),
        200);
    assertThat(ownerCount(a)).isEqualTo(1);
    assertThat(ownerCount(b)).isEqualTo(1);
  }

  @Test
  void rejectsStaleDuplicateUnrelatedSelfAndWrongModeTransfers() throws Exception {
    String a = createProject("转移校验", maintainerId);
    String path = "/admin/users/" + maintainerId;
    assertThat(
            admin
                .call("PATCH", path, disable(maintainerId, List.of(transfer(a, maintainerId))))
                .status())
        .isEqualTo(400);
    assertThat(
            admin
                .call(
                    "PATCH",
                    path,
                    disable(maintainerId, List.of(transfer(a, viewerId), transfer(a, viewerId))))
                .status())
        .isEqualTo(400);
    assertThat(
            admin
                .call("PATCH", path, disable(maintainerId, List.of(transfer(projectId, viewerId))))
                .status())
        .isEqualTo(400);
    assertThat(
            admin
                .call(
                    "PATCH",
                    path,
                    Map.of(
                        "version",
                        0,
                        "status",
                        "ACTIVE",
                        "ownershipTransfers",
                        List.of(transfer(a, viewerId))))
                .status())
        .isEqualTo(400);
    assertThat(
            admin
                .call(
                    "PATCH",
                    path,
                    disable(
                        maintainerId,
                        List.of(
                            Map.of("projectId", a, "version", 99, "successorUserId", viewerId))))
                .status())
        .isEqualTo(409);
    assertThat(ownerCount(a)).isEqualTo(1);
  }

  @Test
  void membershipChangeInvalidatesGovernancePreviewAndNoOpDoesNotBumpVersion() throws Exception {
    long old = projectVersion(projectId);
    accepted(
        admin.call("PUT", base() + "/members/" + viewerId, Map.of("role", "VIEWER", "version", 0)),
        200);
    assertThat(projectVersion(projectId)).isEqualTo(old);
    accepted(
        admin.call(
            "PUT", base() + "/members/" + viewerId, Map.of("role", "MAINTAINER", "version", 0)),
        200);
    assertThat(projectVersion(projectId)).isEqualTo(old + 1);
    assertThat(
            admin
                .call(
                    "PUT",
                    "/admin/projects/" + projectId + "/ownership",
                    Map.of("version", old, "successorUserId", maintainerId, "reason", "过期预览"))
                .status())
        .isEqualTo(409);
  }

  @Test
  void normalUsersCannotGovernAccountsOrCreateProjects() throws Exception {
    Session normal = new Session();
    normal.login("maintainer", fixturePassword);
    for (String path :
        List.of(
            "/admin/projects",
            "/admin/projects/" + projectId + "/ownership",
            "/admin/users/" + viewerId + "/disable-impact"))
      assertThat(normal.call("GET", path, null).status()).isEqualTo(403);
    assertThat(
            normal
                .call(
                    "PUT",
                    "/admin/projects/" + projectId + "/ownership",
                    Map.of(
                        "version",
                        projectVersion(projectId),
                        "successorUserId",
                        maintainerId,
                        "reason",
                        "越权"))
                .status())
        .isEqualTo(403);
    assertThat(
            normal.call("PATCH", "/admin/users/" + viewerId, disable(viewerId, List.of())).status())
        .isEqualTo(403);
    assertThat(
            normal
                .call("POST", "/projects", Map.of("name", "越权创建", "ownerId", maintainerId))
                .status())
        .isEqualTo(403);
  }

  @Test
  void archivedProjectCanBeRepairedWithoutOwnerCooperation() throws Exception {
    String a = createProject("归档交接", maintainerId);
    Session original = new Session();
    original.login("maintainer", fixturePassword);
    accepted(
        original.call(
            "PATCH", "/projects/" + a, Map.of("version", projectVersion(a), "status", "ARCHIVED")),
        200);
    accepted(
        admin.call("PATCH", "/admin/users/" + maintainerId, disable(maintainerId, List.of())), 200);
    assertThat(ownerCount(a)).isZero();
    accepted(
        admin.call(
            "PUT",
            "/admin/projects/" + a + "/ownership",
            Map.of(
                "version",
                projectVersion(a),
                "fromUserId",
                maintainerId,
                "successorUserId",
                viewerId,
                "reason",
                "恢复归档项目责任人")),
        200);
    Session successor = new Session();
    successor.login("viewer", fixturePassword);
    accepted(
        successor.call(
            "PATCH", "/projects/" + a, Map.of("version", projectVersion(a), "status", "ACTIVE")),
        200);
    assertThat(ownerCount(a)).isEqualTo(1);
    accepted(successor.call("DELETE", "/projects/" + a + "/members/" + maintainerId, null), 200);
    assertThat(admin.call("GET", "/projects/" + a, null).status()).isEqualTo(404);
  }

  @Test
  void disabledOwnerCleanupDoesNotCountAsRemovingLastEffectiveOwner() throws Exception {
    accepted(
        admin.call(
            "PUT", base() + "/members/" + maintainerId, Map.of("role", "OWNER", "version", 0)),
        200);
    accepted(
        admin.call("PATCH", "/admin/users/" + maintainerId, disable(maintainerId, List.of())), 200);
    accepted(
        admin.call(
            "PUT", base() + "/members/" + maintainerId, Map.of("role", "VIEWER", "version", 1)),
        200);
    accepted(admin.call("DELETE", base() + "/members/" + maintainerId, null), 200);
    assertThat(ownerCount(projectId)).isEqualTo(1);
  }

  @Test
  void demotionRacingWithDisablePreservesAnEffectiveOwner() throws Exception {
    accepted(
        admin.call(
            "PUT", base() + "/members/" + maintainerId, Map.of("role", "OWNER", "version", 0)),
        200);
    Session second = new Session();
    second.login("maintainer", fixturePassword);
    second.csrf();
    admin.csrf();
    var body = disable(maintainerId, List.of());
    assertThat(
            race(
                () -> admin.call("PATCH", "/admin/users/" + maintainerId, body).status(),
                () ->
                    second
                        .call(
                            "PUT",
                            base() + "/members/" + adminId,
                            Map.of("role", "VIEWER", "version", 0))
                        .status()))
        .allMatch(status -> Set.of(200, 401, 409).contains(status))
        .contains(200);
    assertThat(ownerCount(projectId)).isEqualTo(1);
  }

  @Test
  void concurrentDisablingOfTwoOwnersKeepsOneActive() throws Exception {
    String a = createProject("双所有者禁用", maintainerId);
    accepted(
        admin.call(
            "PUT",
            "/admin/projects/" + a + "/ownership",
            Map.of("version", projectVersion(a), "successorUserId", viewerId, "reason", "增加共同所有者")),
        200);
    Session admin2 = new Session();
    admin2.login(adminUsername, adminPassword);
    admin.csrf();
    admin2.csrf();
    var first = disable(maintainerId, List.of());
    var second = disable(viewerId, List.of());
    assertThat(
            race(
                () -> admin.call("PATCH", "/admin/users/" + maintainerId, first).status(),
                () -> admin2.call("PATCH", "/admin/users/" + viewerId, second).status()))
        .containsExactlyInAnyOrder(200, 409);
    assertThat(ownerCount(a)).isEqualTo(1);
  }

  @Test
  void successorDisableRacingHandoverCannotOrphanProject() throws Exception {
    String a = createProject("继任者竞争", maintainerId);
    Session admin2 = new Session();
    admin2.login(adminUsername, adminPassword);
    admin.csrf();
    admin2.csrf();
    var first = disable(maintainerId, List.of(transfer(a, viewerId)));
    var second = disable(viewerId, List.of());
    assertThat(
            race(
                () -> admin.call("PATCH", "/admin/users/" + maintainerId, first).status(),
                () -> admin2.call("PATCH", "/admin/users/" + viewerId, second).status()))
        .contains(200)
        .allMatch(status -> Set.of(200, 400, 409).contains(status));
    assertThat(ownerCount(a)).isEqualTo(1);
  }

  @Test
  void existingMemberPromotionAndInvalidGovernanceRequestsAreSafe() throws Exception {
    long version = projectVersion(projectId);
    accepted(
        admin.call(
            "PUT",
            "/admin/projects/" + projectId + "/ownership",
            Map.of("version", version, "successorUserId", viewerId, "reason", "增加现有成员为所有者")),
        200);
    assertThat(ownerCount(projectId)).isEqualTo(2);
    assertThat(projectVersion(projectId)).isEqualTo(version + 1);
    int before = jdbc.queryForObject("SELECT COUNT(*) FROM tf_audit_event", Integer.class);
    for (var body :
        List.of(
            Map.of(
                "version",
                version + 1,
                "successorUserId",
                viewerId,
                "fromUserId",
                viewerId,
                "reason",
                "不能交接自己"),
            Map.of(
                "version",
                version + 1,
                "successorUserId",
                maintainerId,
                "fromUserId",
                maintainerId,
                "reason",
                "原成员不是所有者"),
            Map.of("version", version + 1, "successorUserId", maintainerId, "reason", " "))) {
      assertThat(admin.call("PUT", "/admin/projects/" + projectId + "/ownership", body).status())
          .isIn(400, 409);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_audit_event", Integer.class))
        .isEqualTo(before);
  }

  @Test
  void concurrentLastAdminDisablingKeepsAnActiveAdministrator() throws Exception {
    var created =
        accepted(
            admin.call(
                "POST",
                "/admin/users",
                Map.of(
                    "username", "other-admin", "password", fixturePassword, "globalAdmin", true)),
            201);
    String other = created.path("id").asString();
    // Hand project ownership to a normal account so only the administrator invariant is exercised.
    accepted(
        admin.call(
            "PUT",
            "/admin/projects/" + projectId + "/ownership",
            Map.of(
                "version",
                projectVersion(projectId),
                "fromUserId",
                adminId,
                "successorUserId",
                maintainerId,
                "reason",
                "管理员竞争测试准备")),
        200);
    Session second = new Session();
    second.login("other-admin", fixturePassword);
    second.csrf();
    admin.csrf();
    try {
      var firstBody = disable(adminId, List.of());
      var secondBody = disable(other, List.of());
      assertThat(
              race(
                  () -> admin.call("PATCH", "/admin/users/" + adminId, firstBody).status(),
                  () -> second.call("PATCH", "/admin/users/" + other, secondBody).status()))
          .containsExactlyInAnyOrder(200, 409);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM tf_user WHERE global_admin=TRUE AND status='ACTIVE'",
                  Integer.class))
          .isEqualTo(1);
    } finally {
      jdbc.update("UPDATE tf_user SET status='ACTIVE',version=0 WHERE id=?", adminId);
    }
  }

  // Hold a real DB row, then observe the HTTP connection actually waiting in MySQL before changing
  // permissions.
  private void awaitSqlWait(String table) throws Exception {
    long end = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < end) {
      var statements =
          jdbc.queryForList(
              "SELECT INFO FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND INFO"
                  + " IS NOT NULL",
              String.class);
      if (statements.stream()
          .anyMatch(q -> q.contains(table) && q.toUpperCase(Locale.ROOT).contains("FOR UPDATE")))
        return;
      Thread.sleep(20);
    }
    fail("HTTP transaction did not reach the expected database lock");
  }

  @Test
  void permissionIsRecheckedAfterWaitingForProjectLock() throws Exception {
    accepted(
        admin.call(
            "PUT", base() + "/members/" + maintainerId, Map.of("role", "OWNER", "version", 0)),
        200);
    Session second = new Session();
    second.login("maintainer", fixturePassword);
    second.csrf();
    try (var connection = jdbc.getDataSource().getConnection();
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      connection.setAutoCommit(false);
      try (var st =
          connection.prepareStatement("SELECT id FROM tf_project WHERE id=? FOR UPDATE")) {
        st.setString(1, projectId);
        st.executeQuery().close();
      }
      var pending =
          pool.submit(
              () ->
                  second
                      .call(
                          "PUT",
                          base() + "/members/" + viewerId,
                          Map.of("role", "MAINTAINER", "version", 0))
                      .status());
      try {
        awaitSqlWait("tf_project");
        try (var st =
            connection.prepareStatement(
                "UPDATE tf_project_member SET role='VIEWER',version=version+1 WHERE project_id=?"
                    + " AND user_id=?")) {
          st.setString(1, projectId);
          st.setString(2, maintainerId);
          st.executeUpdate();
        }
      } finally {
        connection.commit();
      }
      assertThat(pending.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(403);
      assertThat(
              jdbc.queryForObject(
                  "SELECT role FROM tf_project_member WHERE project_id=? AND user_id=?",
                  String.class,
                  projectId,
                  viewerId))
          .isEqualTo("VIEWER");
    }
  }

  @Test
  void disabledActorSessionIsRecheckedAfterWaitingForUserLock() throws Exception {
    accepted(
        admin.call(
            "PUT", base() + "/members/" + maintainerId, Map.of("role", "OWNER", "version", 0)),
        200);
    Session second = new Session();
    second.login("maintainer", fixturePassword);
    second.csrf();
    try (var connection = jdbc.getDataSource().getConnection();
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      connection.setAutoCommit(false);
      try (var st = connection.prepareStatement("SELECT id FROM tf_user WHERE id=? FOR UPDATE")) {
        st.setString(1, maintainerId);
        st.executeQuery().close();
      }
      var pending =
          pool.submit(
              () ->
                  second
                      .call(
                          "PUT",
                          base() + "/members/" + viewerId,
                          Map.of("role", "MAINTAINER", "version", 0))
                      .status());
      try {
        awaitSqlWait("tf_user");
        try (var st =
            connection.prepareStatement(
                "UPDATE tf_user SET status='DISABLED',version=version+1 WHERE id=?")) {
          st.setString(1, maintainerId);
          st.executeUpdate();
        }
      } finally {
        connection.commit();
      }
      assertThat(pending.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(401);
      assertThat(ownerCount(projectId)).isEqualTo(1);
    }
  }

  @Test
  void viewerAdministratorCanAtomicallyDisableProject1SoleOwnerTest1() throws Exception {
    String test1 = createUser("test1");
    String project1 = createProject("project1", test1);
    Session original = new Session();
    original.login("test1", fixturePassword);
    accepted(
        original.call(
            "PUT", "/projects/" + project1 + "/members/" + adminId, Map.of("role", "VIEWER")),
        200);
    assertThat(admin.call("GET", "/projects/" + project1 + "/members", null).status())
        .isEqualTo(403);
    var ownership =
        accepted(admin.call("GET", "/admin/projects/" + project1 + "/ownership", null), 200);
    assertThat(ownership.path("project").path("activeOwners").asInt()).isEqualTo(1);
    var rejected = admin.call("PATCH", "/admin/users/" + test1, disable(test1, List.of()));
    assertThat(rejected.status()).isEqualTo(409);
    assertThat(rejected.body().at("/error/code").asString()).isEqualTo("OWNER_TRANSFER_REQUIRED");
    assertThat(rejected.body().at("/error/fieldErrors/0/id").asString()).isEqualTo(project1);
    assertThat(rejected.body().at("/error/fieldErrors/0/name").asString()).isEqualTo("project1");
    var impact =
        accepted(admin.call("GET", "/admin/users/" + test1 + "/disable-impact", null), 200);
    assertThat(impact.at("/projects/0/requiresTransfer").asBoolean()).isTrue();
    accepted(
        admin.call(
            "PATCH",
            "/admin/users/" + test1,
            disable(test1, List.of(transfer(project1, viewerId)))),
        200);
    assertThat(ownerCount(project1)).isEqualTo(1);
    assertThat(original.call("GET", "/auth/me", null).status()).isEqualTo(401);
    assertThat(
            jdbc.queryForObject(
                "SELECT role FROM tf_project_member WHERE project_id=? AND user_id=?",
                String.class,
                project1,
                adminId))
        .isEqualTo("VIEWER");
    assertThat(admin.call("POST", "/projects/" + project1 + "/cases", caseInput("禁止隐式提权")).status())
        .isEqualTo(403);
    Session normal = new Session();
    normal.login("viewer", fixturePassword);
    assertThat(normal.call("GET", "/admin/projects/" + project1 + "/ownership", null).status())
        .isEqualTo(403);
  }

  @Test
  void staleDisablePreviewReturnsNewlyBlockingProjectAndRollsBackExistingHandover()
      throws Exception {
    String first = createProject("已有交接", maintainerId);
    var before =
        accepted(admin.call("GET", "/admin/users/" + maintainerId + "/disable-impact", null), 200);
    assertThat(before.path("projects").size()).isEqualTo(1);
    String added = createProject("并发新增阻塞项目", maintainerId);
    int auditCount = jdbc.queryForObject("SELECT COUNT(*) FROM tf_audit_event", Integer.class);
    var stale =
        admin.call(
            "PATCH",
            "/admin/users/" + maintainerId,
            disable(maintainerId, List.of(transfer(first, viewerId))));
    assertThat(stale.status()).isEqualTo(409);
    assertThat(stale.body().at("/error/fieldErrors/0/id").asString()).isEqualTo(added);
    assertThat(stale.body().at("/error/fieldErrors/0/name").asString()).isEqualTo("并发新增阻塞项目");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tf_audit_event", Integer.class))
        .isEqualTo(auditCount);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM tf_project_member WHERE project_id=? AND user_id=?",
                Integer.class,
                first,
                viewerId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM tf_user WHERE id=?", String.class, maintainerId))
        .isEqualTo("ACTIVE");
    accepted(
        admin.call(
            "PATCH",
            "/admin/users/" + maintainerId,
            disable(maintainerId, List.of(transfer(first, viewerId), transfer(added, viewerId)))),
        200);
    assertThat(ownerCount(first)).isEqualTo(1);
    assertThat(ownerCount(added)).isEqualTo(1);
  }

  private record Reply(int status, JsonNode body, HttpHeaders headers) {
    JsonNode data() {
      return body.path("data");
    }
  }

  private class Session {
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .connectTimeout(java.time.Duration.ofSeconds(3))
            .build();
    private String token;

    void csrf() throws Exception {
      token = raw("GET", "/auth/csrf", null, false).data().path("token").asString();
    }

    String cookie() {
      return cookies.getCookieStore().getCookies().stream()
          .filter(c -> c.getName().equals("TESTFLOW_SESSION"))
          .map(HttpCookie::getValue)
          .findFirst()
          .orElse("");
    }

    void login(String username, String password) throws Exception {
      accepted(
          call("POST", "/auth/login", Map.of("username", username, "password", password)), 200);
      token = null;
    }

    Reply call(String method, String path, Object body) throws Exception {
      return raw(method, path, body, true);
    }

    Reply chunked(String path, String body) throws Exception {
      if (token == null) csrf();
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
              .timeout(java.time.Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .header("X-CSRF-TOKEN", token)
              .POST(
                  HttpRequest.BodyPublishers.ofInputStream(
                      () -> new java.io.ByteArrayInputStream(bytes)))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return new Reply(response.statusCode(), json.readTree(response.body()), response.headers());
    }

    Reply raw(String method, String path, Object body, boolean withCsrf) throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
              .timeout(java.time.Duration.ofSeconds(10))
              .header("Accept", "application/json");
      if (!method.equals("GET")) {
        if (withCsrf) {
          if (token == null) csrf();
          request.header("X-CSRF-TOKEN", token);
        }
        request.header("Content-Type", "application/json");
      }
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new Reply(response.statusCode(), json.readTree(response.body()), response.headers());
    }
  }
}
