package com.testflow.core.execution;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ExecutionCoreTest {
  private final JsonMapper json = JsonMapper.builder().build();

  private String key() {
    return Base64.getEncoder().encodeToString(java.security.SecureRandom.getSeed(32));
  }

  private ExecutionSnapshot snapshot() {
    return new ExecutionSnapshot(
        1,
        101,
        1,
        2,
        201,
        0,
        "http://allowed.test:8088",
        Map.of("token", "synthetic-secret"),
        Set.of("token"),
        List.of(new ExecutionSnapshot.TargetRule(1, "http", "allowed.test", 8088, "LOOPBACK")),
        List.of(new ExecutionSnapshot.Step(3, "fixture", 0, json.readTree("{\"request\":{}}"))));
  }

  @Test
  void snapshotIsAuthenticatedEncryptedAndDomainSeparated() {
    String encryption = key(), hmac = key();
    var codec = new SnapshotCodec(json, encryption, hmac, 4194304);
    var encoded = codec.encode(snapshot());
    assertThat(encoded.ciphertext()).doesNotContain("synthetic-secret");
    assertThat(codec.decode(encoded.ciphertext(), encoded.hmac())).isEqualTo(snapshot());
    assertThatThrownBy(() -> codec.decode(encoded.ciphertext(), "0".repeat(64)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                new SnapshotCodec(json, key(), hmac, 4194304)
                    .decode(encoded.ciphertext(), encoded.hmac()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> new com.testflow.core.SecretCipher(encryption).decrypt(encoded.ciphertext()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(codec.encode(snapshot()).ciphertext()).isNotEqualTo(encoded.ciphertext());
  }

  @Test
  void canonicalRequestPreservesArraysAndIgnoresObjectKeyOrdering() {
    var codec = new SnapshotCodec(json, key(), key(), 4194304);
    assertThat(codec.requestHash(Map.of("a", 1, "b", List.of(1, 2))))
        .isEqualTo(codec.requestHash(new LinkedHashMap<>(Map.of("b", List.of(1, 2), "a", 1))))
        .isNotEqualTo(codec.requestHash(Map.of("a", 1, "b", List.of(2, 1))));
  }

  @Test
  void snapshotAndTemplateExpansionHaveFiniteLimits() {
    var codec = new SnapshotCodec(json, key(), key(), 1024);
    var larger =
        new ExecutionSnapshot(
            1,
            101,
            1,
            2,
            201,
            0,
            "http://allowed.test",
            Map.of("large", "x".repeat(2000)),
            Set.of(),
            List.of(),
            snapshot().steps());
    assertThatThrownBy(() -> codec.encode(larger)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new VariableTemplate(json)
                    .bind("${env.x}".repeat(2000), Map.of("x", "x".repeat(4096))))
        .isInstanceOf(ExecutionFailure.class)
        .hasMessage("TEMPLATE_TOO_LARGE");
  }

  @Test
  void jsonVariablesRemainStringsAndCannotInjectFields() {
    var binder = new VariableTemplate(json);
    var bound =
        binder.bind(
            json.readTree("{\"data\":\"${env.input}\",\"literal\":null}"),
            Map.of("input", "\"},\"admin\":true"));
    assertThat(bound.path("data").asString()).isEqualTo("\"},\"admin\":true");
    assertThat(bound.has("admin")).isFalse();
    assertThat(bound.path("literal").isNull()).isTrue();
    assertThatThrownBy(() -> binder.bind("${run.token}", Map.of()))
        .hasMessage("VARIABLE_NOT_FOUND");
    assertThatThrownBy(() -> binder.bind("${env.missing}", Map.of()))
        .hasMessage("VARIABLE_NOT_FOUND");
    assertThatThrownBy(() -> binder.bind("${env.incomplete", Map.of()))
        .hasMessage("VARIABLE_REFERENCE_INVALID");
  }

  @Test
  void privateLoopbackAndPublicTargetsRequireTheirExactScope() throws Exception {
    var guard = new TargetGuard(Set.of(8080, 13306, 6379));
    var local = new ExecutionSnapshot.TargetRule(1, "http", "allowed.test", 8088, "LOOPBACK");
    guard.validate(
        local,
        new InetAddress[] {InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")});
    assertThat(guard.scope(InetAddress.getByName("::ffff:127.0.0.1"))).isEqualTo("LOOPBACK");
    assertThat(guard.scope(InetAddress.getByName("10.1.2.3"))).isEqualTo("PRIVATE");
    assertThat(guard.scope(InetAddress.getByName("2606:4700:4700::1111"))).isEqualTo("PUBLIC");
    assertThatThrownBy(
            () ->
                guard.validate(
                    local,
                    new InetAddress[] {
                      InetAddress.getByName("127.0.0.1"), InetAddress.getByName("8.8.8.8")
                    }))
        .hasMessage("TARGET_ADDRESS_BLOCKED");
    assertThatThrownBy(
            () -> guard.authorize(URI.create("http://allowed.test:8089"), List.of(local)))
        .hasMessage("TARGET_NOT_AUTHORIZED");
  }

  @Test
  void metadataSpecialRangesAndControlPortsCannotBeAuthorized() throws Exception {
    var guard = new TargetGuard(Set.of(8080, 13306, 6379));
    for (String address :
        List.of(
            "169.254.169.254",
            "100.100.100.200",
            "0.0.0.0",
            "224.0.0.1",
            "::",
            "fe80::1",
            "fd00:ec2::254",
            "2002:7f00:1::")) {
      assertThatThrownBy(() -> guard.scope(InetAddress.getByName(address)))
          .as(address)
          .hasMessage("TARGET_ADDRESS_BLOCKED");
    }
    var rule = new ExecutionSnapshot.TargetRule(1, "http", "127.0.0.1", 8080, "LOOPBACK");
    assertThatThrownBy(() -> guard.authorize(URI.create("http://127.0.0.1:8080"), List.of(rule)))
        .hasMessage("TARGET_ADDRESS_BLOCKED");
    assertThatThrownBy(() -> guard.authorize(URI.create("http://mysql:3306"), List.of()))
        .hasMessage("TARGET_BLOCKED");
    var infrastructure = NetworkBoundary.guard("jdbc:mysql://127.0.0.1:13306/testflow", 8080, 6379);
    for (int port : List.of(9092, 9093, 19092, 29092)) {
      var authorized = new ExecutionSnapshot.TargetRule(1, "http", "127.0.0.1", port, "LOOPBACK");
      assertThatThrownBy(
              () ->
                  infrastructure.authorize(
                      URI.create("http://127.0.0.1:" + port), List.of(authorized)))
          .hasMessage("TARGET_ADDRESS_BLOCKED");
    }
  }

  @Test
  void incompleteResultsNeverImplyPassingOrZeroFailure() {
    var empty = RunMetrics.of(List.of());
    assertThat(empty.passRate()).isNull();
    assertThat(empty.progress()).isNull();
    var unknown = RunMetrics.of(List.of("UNKNOWN"));
    assertThat(unknown.progress()).isEqualTo(100);
    assertThat(unknown.coverage()).isZero();
    assertThat(unknown.passRate()).isNull();
    assertThat(unknown.outcome("INTERRUPTED")).isEqualTo("INCOMPLETE");
    var failure =
        RunMetrics.of(List.of("PASSED", "FAILED", "ERROR", "SKIPPED", "CANCELLED", "UNKNOWN"));
    assertThat(failure.known()).isEqualTo(3);
    assertThat(failure.passRate()).isEqualTo(33.33);
    assertThat(failure.coverage()).isEqualTo(50);
    assertThat(failure.outcome("COMPLETED")).isEqualTo("FAILED");
    assertThat(failure.outcome("CANCELLED")).isEqualTo("INCOMPLETE");
  }
}
