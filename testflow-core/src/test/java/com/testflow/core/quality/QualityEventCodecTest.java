package com.testflow.core.quality;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class QualityEventCodecTest {
  final QualityEventCodec codec = new QualityEventCodec();

  String valid() {
    return new JsonMapper()
        .writeValueAsString(
            new RunFinalized(
                1,
                "00000000-0000-0000-0000-000000000001",
                "1",
                "2",
                "PLAN",
                "3",
                "4",
                "2026-11-18T00:00:00.000000Z",
                "COMPLETED",
                "PASSED",
                1,
                1,
                0,
                0,
                0,
                0,
                0,
                5L));
  }

  @Test
  void roundTripUsesImmutableSummaryAndStableDigest() {
    var event = codec.decode(valid());
    assertThat(event.total()).isEqualTo(1);
    assertThat(codec.fingerprint(event)).hasSize(64);
    assertThat(codec.fingerprint(codec.decode(valid()))).isEqualTo(codec.fingerprint(event));
  }

  @Test
  void rejectsUnsupportedVersionsUnknownFieldsAndInvalidCounts() {
    for (String message :
        new String[] {
          valid().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
          valid().replace("\"passed\":1", "\"passed\":-1"),
          valid().replace("\"failed\":0,", ""),
          valid().replace("\"failed\":0", "\"failed\":null"),
          valid()
              .replace("\"passed\":1", "\"passed\":2147483647")
              .replace("\"failed\":0", "\"failed\":2147483647")
              .replace("\"errors\":0", "\"errors\":3")
              .replace("\"PASSED\"", "\"FAILED\""),
          valid().replace("\"PASSED\"", "\"FAILED\""),
          valid().replace("\"durationMs\":5", "\"durationMs\":-1"),
          valid().replaceFirst("\\{", "{\"untrusted\":true,")
        }) assertThatThrownBy(() -> codec.decode(message)).hasMessage("INVALID_QUALITY_EVENT");
  }

  @Test
  void boundedParsingAndDiagnosticsDoNotExposeRawInput() {
    String secret = "synthetic-secret-canary";
    assertThatThrownBy(() -> codec.decode(secret + "x".repeat(16385)))
        .hasMessage("INVALID_QUALITY_EVENT");
    assertThat(codec.trustedEventId(secret)).isNull();
    assertThatThrownBy(() -> codec.decode("[".repeat(30) + "]".repeat(30)))
        .hasMessage("INVALID_QUALITY_EVENT");
  }
}
