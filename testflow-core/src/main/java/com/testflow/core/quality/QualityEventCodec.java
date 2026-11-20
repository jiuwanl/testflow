package com.testflow.core.quality;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;

public final class QualityEventCodec {
  private final JsonMapper json =
      JsonMapper.builder(
              tools.jackson.core.json.JsonFactory.builder()
                  .streamReadConstraints(
                      tools.jackson.core.StreamReadConstraints.builder()
                          .maxNestingDepth(8)
                          .maxStringLength(16384)
                          .maxNumberLength(32)
                          .build())
                  .build())
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .build();

  public RunFinalized decode(String payload) {
    try {
      if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > 16384)
        throw new IllegalArgumentException();
      return json.readValue(payload, RunFinalized.class);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("INVALID_QUALITY_EVENT");
    }
  }

  public String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException();
    }
  }

  public String fingerprint(RunFinalized event) {
    return hash(json.writeValueAsString(event));
  }

  public String trustedEventId(String payload) {
    try {
      if (payload == null || payload.length() > 16384) return null;
      var id = json.readTree(payload).path("eventId").asString();
      return java.util.UUID.fromString(id).toString().equals(id) ? id : null;
    } catch (RuntimeException error) {
      return null;
    }
  }
}
