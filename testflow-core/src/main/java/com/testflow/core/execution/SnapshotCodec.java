package com.testflow.core.execution;

import com.testflow.core.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;

/** Authentication, canonicalization and encryption never expose plaintext in diagnostics. */
public final class SnapshotCodec {
  private final JsonMapper json;
  private final SecretCipher cipher;
  private final SecretKeySpec hmacKey;
  private final int maxBytes;

  public SnapshotCodec(JsonMapper json, String encryptionKey, String hmacKey, int maxBytes) {
    this.json = json;
    this.cipher = new SecretCipher(encryptionKey, "testflow:snapshot:v1");
    byte[] key;
    try {
      key = Base64.getDecoder().decode(hmacKey);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("Snapshot HMAC key must encode 32 bytes");
    }
    if (key.length != 32)
      throw new IllegalArgumentException("Snapshot HMAC key must encode 32 bytes");
    if (Arrays.equals(key, Base64.getDecoder().decode(encryptionKey)))
      throw new IllegalArgumentException("Snapshot HMAC and encryption keys must differ");
    this.hmacKey = new SecretKeySpec(key, "HmacSHA256");
    Arrays.fill(key, (byte) 0);
    if (maxBytes < 1024 || maxBytes > 4 * 1024 * 1024)
      throw new IllegalArgumentException("Invalid snapshot limit");
    this.maxBytes = maxBytes;
  }

  public record Encoded(String ciphertext, String hmac) {}

  public Encoded encode(ExecutionSnapshot snapshot) {
    String content = canonical(snapshot);
    if (content.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      throw new IllegalArgumentException("Execution snapshot exceeds the configured limit");
    }
    return new Encoded(cipher.encrypt(content), authenticate(content));
  }

  public ExecutionSnapshot decode(String ciphertext, String expectedHmac) {
    try {
      String content = cipher.decrypt(ciphertext);
      if (content.getBytes(StandardCharsets.UTF_8).length > maxBytes
          || !MessageDigest.isEqual(
              authenticate(content).getBytes(StandardCharsets.US_ASCII),
              expectedHmac.getBytes(StandardCharsets.US_ASCII))) throw new IllegalStateException();
      var payload = json.readTree(content);
      // Authenticate the exact historical bytes before adding v1's implicit defaults in memory.
      if (payload.path("schemaVersion").asInt() == 1 && payload.isObject()) {
        var legacy = (tools.jackson.databind.node.ObjectNode) payload;
        legacy.put("failFast", false);
        legacy.putNull("planVersion");
      }
      if (payload.path("schemaVersion").asInt() < 3 && payload.isObject()) {
        var legacy = (tools.jackson.databind.node.ObjectNode) payload;
        legacy.put("executionMode", "ORDERED");
        legacy.put("maxConcurrency", 1);
      }
      ExecutionSnapshot snapshot = json.treeToValue(payload, ExecutionSnapshot.class);
      ExecutionPolicy.validate(snapshot.executionMode(), snapshot.maxConcurrency());
      if ("INDEPENDENT".equals(snapshot.executionMode()))
        for (var step : snapshot.steps()) ExecutionPolicy.independent(step.config());
      if (!Set.of(1, 2, 3).contains(snapshot.schemaVersion())
          || snapshot.steps().isEmpty()
          || snapshot.steps().size() > 100
          || (snapshot.schemaVersion() == 1 && snapshot.steps().size() != 1)
          || snapshot.runId() <= 0
          || snapshot.environmentId() <= 0
          || snapshot.environmentVersion() < 0) throw new IllegalStateException();
      return snapshot;
    } catch (RuntimeException ex) {
      throw new IllegalStateException("Execution snapshot cannot be authenticated or decoded");
    }
  }

  public String canonical(Object value) {
    return json.writeValueAsString(sorted(json.convertValue(value, Object.class)));
  }

  public String requestHash(Object request) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical(request).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException ex) {
      throw new IllegalStateException("Request hashing unavailable");
    }
  }

  private Object sorted(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new TreeMap<>();
      map.forEach((key, item) -> result.put(key.toString(), sorted(item)));
      return result;
    }
    if (value instanceof List<?> list) return list.stream().map(this::sorted).toList();
    // Sets in a snapshot are semantic sets, not execution order.
    if (value instanceof Set<?> set) return set.stream().map(Object::toString).sorted().toList();
    return value;
  }

  private String authenticate(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(hmacKey);
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException ex) {
      throw new IllegalStateException("Snapshot authentication unavailable");
    }
  }
}
