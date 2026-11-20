package com.testflow.api;

import java.net.*;
import java.net.http.*;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real HTTP test client. It never includes request payloads/credentials in assertion diagnostics.
 */
final class ApiTestSession {
  private final String root;
  private final JsonMapper json;
  private final HttpClient client =
      HttpClient.newBuilder()
          .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
          .build();

  record Reply(int status, JsonNode body) {
    JsonNode data() {
      return body.path("data");
    }
  }

  ApiTestSession(int port, JsonMapper json) {
    root = "http://127.0.0.1:" + port + "/api/v1";
    this.json = json;
  }

  Reply call(String method, String path, Object body) throws Exception {
    return call(method, path, body, null);
  }

  Reply call(String method, String path, Object body, String key) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(root + path)).timeout(java.time.Duration.ofSeconds(20));
    if (!"GET".equals(method)) {
      var csrf =
          client.send(
              HttpRequest.newBuilder(URI.create(root + "/auth/csrf")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      JsonNode token = json.readTree(csrf.body()).path("data");
      request
          .header(token.path("headerName").asString(), token.path("token").asString())
          .header("Content-Type", "application/json");
    }
    if (key != null) request.header("Idempotency-Key", key);
    var response =
        client.send(
            request
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return new Reply(response.statusCode(), json.readTree(response.body()));
  }

  void login(String username, String password) throws Exception {
    if (call("POST", "/auth/login", Map.of("username", username, "password", password)).status()
        != 200) throw new AssertionError("Fixture login failed; credentials deliberately omitted");
    // Materialize the new session's token before concurrent writes; two first-token creations can
    // race.
    if (call("GET", "/auth/csrf", null).status() != 200)
      throw new AssertionError("Fixture CSRF initialization failed");
  }
}
