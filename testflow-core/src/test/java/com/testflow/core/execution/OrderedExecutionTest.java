package com.testflow.core.execution;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OrderedExecutionTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void typedJsonAndTextPositionsUseSeparateNamespaces() {
    var run = Map.of("id", json.readTree("42"), "flag", json.readTree("true"));
    var config =
        json.readTree(
            """
{"request":{"path":"/orders/${run.id}","query":[{"name":"id","value":"${run.id}"}],"body":{"type":"JSON","json":{"id":"${run.id}","flag":"${run.flag}","text":"id=${run.id}","env":"${env.id}"}}}}
""");
    var bound = new VariableTemplate(json).bind(config, Map.of("id", "env-value"), run);
    assertThat(bound.at("/request/body/json/id").isIntegralNumber()).isTrue();
    assertThat(bound.at("/request/body/json/flag").isBoolean()).isTrue();
    assertThat(bound.at("/request/query/0/value").asString()).isEqualTo("42");
    assertThat(bound.at("/request/body/json/env").asString()).isEqualTo("env-value");
    assertThat(bound.at("/request/body/json/text").asString()).isEqualTo("id=42");
    assertThat(
            new VariableTemplate(json)
                .bind(
                    "${run.id}",
                    Map.of(),
                    Map.of("id", json.getNodeFactory().stringNode("${env.never}"))))
        .isEqualTo("${env.never}");
    assertThatThrownBy(() -> new VariableTemplate(json).bind(config, Map.of("id", "x"), Map.of()))
        .hasMessage("VARIABLE_NOT_FOUND");
  }

  @Test
  void multipleStepProtocolPreservesOrderOptionsAndOldSingleCase() {
    String enc = Base64.getEncoder().encodeToString(new byte[32]);
    byte[] key = new byte[32];
    key[0] = 1;
    var codec = new SnapshotCodec(json, enc, Base64.getEncoder().encodeToString(key), 4194304);
    var steps =
        List.of(
            new ExecutionSnapshot.Step(7, "first", 0, json.createObjectNode()),
            new ExecutionSnapshot.Step(7, "repeat", 1, json.createObjectNode()));
    var value =
        new ExecutionSnapshot(
            2,
            1,
            2,
            3,
            4,
            0,
            "http://fixture.test",
            Map.of(),
            Set.of(),
            List.of(),
            steps,
            true,
            9L);
    var stored = codec.encode(value);
    var decoded = codec.decode(stored.ciphertext(), stored.hmac());
    assertThat(decoded.steps().stream().map(ExecutionSnapshot.Step::name))
        .containsExactly("first", "repeat");
    assertThat(decoded.failFast()).isTrue();
    assertThat(decoded.planVersion()).isEqualTo(9);
    var old =
        new ExecutionSnapshot(
            1,
            1,
            2,
            3,
            4,
            0,
            "http://fixture.test",
            Map.of(),
            Set.of(),
            List.of(),
            steps.subList(0, 1));
    var oldStored = codec.encode(old);
    assertThat(codec.decode(oldStored.ciphertext(), oldStored.hmac()).failFast()).isFalse();
  }

  @Test
  void readsTheActualStageTwoPayloadWithoutNewCreatorFields() throws Exception {
    String encryption = Base64.getEncoder().encodeToString(new byte[32]);
    byte[] key = new byte[32];
    key[0] = 2;
    String authentication = Base64.getEncoder().encodeToString(key);
    var codec = new SnapshotCodec(json, encryption, authentication, 4194304);
    var original =
        new ExecutionSnapshot(
            1,
            1,
            2,
            3,
            4,
            0,
            "http://fixture.test",
            Map.of(),
            Set.of(),
            List.of(),
            List.of(new ExecutionSnapshot.Step(7, "old", 0, json.createObjectNode())));
    var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(original);
    node.remove("failFast");
    node.remove("planVersion");
    node.remove("executionMode");
    node.remove("maxConcurrency");
    String content = codec.canonical(node);
    var mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
    String hmac =
        HexFormat.of()
            .formatHex(mac.doFinal(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    String encrypted =
        new com.testflow.core.SecretCipher(encryption, "testflow:snapshot:v1").encrypt(content);
    assertThat(codec.decode(encrypted, hmac).steps().getFirst().name()).isEqualTo("old");
    assertThat(codec.decode(encrypted, hmac).failFast()).isFalse();
  }

  @Test
  void typedSecretScalarsAreMaskedInRequestJsonAsWellAsText() {
    var snapshot =
        new ExecutionSnapshot(
            2, 1, 2, 3, 4, 0, "http://fixture.test", Map.of(), Set.of(), List.of(), List.of());
    var sanitizer =
        new ReportSanitizer(
            json,
            snapshot,
            Map.of("pin", json.readTree("1234"), "flag", json.readTree("true")),
            Set.of("pin", "flag"));
    assertThat(sanitizer.json(json.readTree("1234"))).isEqualTo("[REDACTED]");
    assertThat(sanitizer.json(json.readTree("true"))).isEqualTo("[REDACTED]");
    assertThat(sanitizer.json(json.readTree("42"))).isEqualTo(42);
    assertThat(sanitizer.scrub("id=1234")).isEqualTo("id=[REDACTED]");
  }

  @Test
  void allResultPairsRespectMetricsAndZeroDenominators() {
    for (String a : List.of("PASSED", "FAILED", "ERROR", "SKIPPED", "CANCELLED", "UNKNOWN"))
      for (String b : List.of("PASSED", "FAILED", "ERROR", "SKIPPED", "CANCELLED", "UNKNOWN")) {
        var m = RunMetrics.of(List.of(a, b));
        assertThat(m.progress()).isEqualTo(100.0);
        int known = m.passed() + m.failed() + m.errors();
        assertThat(m.coverage()).isEqualTo(known * 50.0);
        if (known == 0) assertThat(m.passRate()).isNull();
        else assertThat(m.passRate()).isEqualTo(m.passed() * 100.0 / known);
        assertThat(m.outcome("CANCELLED")).isEqualTo("INCOMPLETE");
        assertThat(m.outcome("INTERRUPTED")).isEqualTo("INCOMPLETE");
        assertThat(m.outcome("TIMED_OUT")).isEqualTo("INCOMPLETE");
      }
  }

  @Test
  void extractionOnlyPublishesAfterAllAssertionsAndExtractorsSucceed() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            byte[] data =
                "{\"data\":{\"id\":42,\"token\":\"synthetic-ephemeral\"}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
          } finally {
            exchange.close();
          }
        });
    server.start();
    int port = server.getAddress().getPort();
    try (var engine = new SafeHttpExecutor(json, new TargetGuard(Set.of(3306)), 1048576, 3000, 2)) {
      for (int mode = 0; mode < 3; mode++) {
        var config =
            json.readTree(
                """
{"request":{"method":"GET","path":"/","query":[],"headers":[],"body":{"type":"NONE"}},"assertions":[{"id":"status","type":"STATUS_CODE","expected":200}],"extractors":[{"name":"id","path":"$.data.id","secret":false},{"name":"token","path":"$.data.token","secret":true}]}
""");
        if (mode == 1)
          ((tools.jackson.databind.node.ObjectNode) config.at("/assertions/0"))
              .put("expected", 201);
        if (mode == 2)
          ((tools.jackson.databind.node.ObjectNode) config.at("/extractors/1"))
              .put("path", "$.data.missing");
        var snapshot =
            new ExecutionSnapshot(
                2,
                1,
                2,
                3,
                4,
                0,
                "http://127.0.0.1:" + port,
                Map.of(),
                Set.of(),
                List.of(new ExecutionSnapshot.TargetRule(1, "http", "127.0.0.1", port, "LOOPBACK")),
                List.of(new ExecutionSnapshot.Step(1, "fixture", 0, config)),
                false,
                0L);
        var result = engine.execute(snapshot, new ExecutionControl(), () -> true);
        if (mode == 0) {
          assertThat(result.state()).isEqualTo("PASSED");
          assertThat(result.outputs().get("id").asInt()).isEqualTo(42);
          assertThat(result.outputSecrets()).contains("token");
        } else {
          assertThat(result.state()).isEqualTo(mode == 1 ? "FAILED" : "ERROR");
          assertThat(result.outputs()).isEmpty();
        }
        assertThat(result.bodyAvailable()).isFalse();
        assertThat(json.writeValueAsString(result.report())).doesNotContain("synthetic-ephemeral");
        assertThat(result.toString()).doesNotContain("synthetic-ephemeral");
      }
    } finally {
      server.stop(0);
    }
  }
}
