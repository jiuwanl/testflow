package com.testflow.core.execution;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ExecutionPolicyTest {
  final JsonMapper json = JsonMapper.builder().build();

  @Test
  void enforcesIndependentLimitsAndOrderedSerialContract() {
    ExecutionPolicy.validate("ORDERED", 1);
    for (int n = 1; n <= 4; n++) ExecutionPolicy.validate("INDEPENDENT", n);
    for (int n : new int[] {0, 2, 4})
      assertThatThrownBy(() -> ExecutionPolicy.validate("ORDERED", n))
          .isInstanceOf(IllegalArgumentException.class);
    for (int n : new int[] {0, 5})
      assertThatThrownBy(() -> ExecutionPolicy.validate("INDEPENDENT", n))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExecutionPolicy.validate("UNKNOWN", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsRunReferencesAcrossAllJsonPositionsButAllowsEnvironmentReferences() {
    for (String input :
        List.of(
            "{\"path\":\"/${run.id}\"}",
            "{\"body\":[{\"id\":\"${run.id}\"}]}",
            "{\"${run.key}\":1}"))
      assertThatThrownBy(() -> ExecutionPolicy.independent(json.readTree(input)))
          .isInstanceOf(IllegalArgumentException.class);
    ExecutionPolicy.independent(
        json.readTree("{\"path\":\"/${env.id}\",\"extractors\":[{\"name\":\"id\"}]}"));
  }

  @Test
  void authenticatesHistoricalV2BytesBeforeApplyingSerialDefaults() throws Exception {
    String encryption = Base64.getEncoder().encodeToString(new byte[32]);
    byte[] key = new byte[32];
    key[0] = 3;
    var codec =
        new SnapshotCodec(json, encryption, Base64.getEncoder().encodeToString(key), 4194304);
    var old =
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
            List.of(new ExecutionSnapshot.Step(7, "old", 0, json.createObjectNode())),
            true,
            9L);
    var payload = (tools.jackson.databind.node.ObjectNode) json.valueToTree(old);
    payload.remove("executionMode");
    payload.remove("maxConcurrency");
    String content = codec.canonical(payload);
    var mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
    String hmac =
        HexFormat.of()
            .formatHex(mac.doFinal(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var cipher = new com.testflow.core.SecretCipher(encryption, "testflow:snapshot:v1");
    var decoded = codec.decode(cipher.encrypt(content), hmac);
    assertThat(decoded.executionMode()).isEqualTo("ORDERED");
    assertThat(decoded.maxConcurrency()).isEqualTo(1);
    assertThat(decoded.failFast()).isTrue();
    assertThat(decoded.planVersion()).isEqualTo(9);
    assertThatThrownBy(() -> codec.decode(cipher.encrypt(content.replace("old", "tampered")), hmac))
        .isInstanceOf(IllegalStateException.class);
  }
}
