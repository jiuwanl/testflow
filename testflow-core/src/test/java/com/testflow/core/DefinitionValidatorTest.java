package com.testflow.core;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class DefinitionValidatorTest {
  private final JsonMapper json = JsonMapper.builder().build();

  private ObjectNode definition() {
    return (ObjectNode)
        json.readTree(
            """
{"request":{"method":"GET","path":"/items/${env.id}","query":[],"headers":[],"body":{"type":"NONE"}},
 "assertions":[{"id":"status","type":"STATUS_CODE","expected":200}],"extractors":[],"timeoutMs":15000}
""");
  }

  @Test
  void acceptsStoredTemplatesWithoutExecutingAnything() {
    assertThatCode(() -> DefinitionValidator.caseConfig(definition())).doesNotThrowAnyException();
  }

  @Test
  void rejectsAuthorityChangesAndUnsafeMethods() {
    for (String path :
        new String[] {"//example.com/x", "https://example.com/x", "/x\\evil", "/x\r\nHost: evil"}) {
      var definition = definition();
      ((ObjectNode) definition.path("request")).put("path", path);
      assertThatThrownBy(() -> DefinitionValidator.caseConfig(definition))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var definition = definition();
    ((ObjectNode) definition.path("request")).put("method", "CONNECT");
    assertThatThrownBy(() -> DefinitionValidator.caseConfig(definition))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsClientControlledRoutingHeadersAndExpressions() {
    var definition = definition();
    ((ObjectNode) definition.path("request"))
        .set("headers", json.readTree("[{\"name\":\"Host\",\"value\":\"elsewhere\"}]"));
    assertThatThrownBy(() -> DefinitionValidator.caseConfig(definition))
        .isInstanceOf(IllegalArgumentException.class);
    var invalid = definition();
    invalid.set(
        "assertions",
        json.readTree("[{\"id\":\"script\",\"type\":\"JSON_EXISTS\",\"path\":\"$..secret\"}]"));
    assertThatThrownBy(() -> DefinitionValidator.caseConfig(invalid))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsCredentialsInBaseUrlAndWildcardHosts() {
    assertThatThrownBy(() -> DefinitionValidator.baseUrl("http://user:secret@example.com"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DefinitionValidator.host("*.example.com"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(DefinitionValidator.host("EXAMPLE.COM.")).isEqualTo("example.com");
    assertThat(DefinitionValidator.baseUrl("http://127.0.0.1:8088/"))
        .isEqualTo("http://127.0.0.1:8088/");
  }

  @Test
  void roleHierarchyDoesNotGrantWriteToViewer() {
    assertThat(ProjectRole.VIEWER.includes(ProjectRole.MAINTAINER)).isFalse();
    assertThat(ProjectRole.OWNER.includes(ProjectRole.MAINTAINER)).isTrue();
  }
}
