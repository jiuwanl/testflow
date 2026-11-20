package com.testflow.core.execution;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SafeHttpExecutorTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private HttpServer server;
  private ExecutorService fixtureThreads;
  private SafeHttpExecutor engine;
  private final AtomicInteger calls = new AtomicInteger(),
      followed = new AtomicInteger(),
      dnsCalls = new AtomicInteger();
  private int port;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    fixtureThreads =
        new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16),
            Thread.ofPlatform().daemon().factory());
    server.setExecutor(fixtureThreads);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/drop")) return;
            if (path.equals("/followed")) followed.incrementAndGet();
            if (path.equals("/redirect"))
              exchange
                  .getResponseHeaders()
                  .set("Location", "http://allowed.test:" + port + "/followed");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (path.equals("/headers"))
              for (int i = 0; i < 200; i++)
                exchange.getResponseHeaders().add("X-Fixture-" + i, "bounded");
            if (path.equals("/oversize-stream")) {
              exchange.sendResponseHeaders(200, 0);
              for (int i = 0; i < 100; i++) {
                exchange.getResponseBody().write(new byte[1024]);
                exchange.getResponseBody().flush();
                Thread.sleep(30);
              }
              return;
            }
            if (path.equals("/slow")) {
              exchange.sendResponseHeaders(200, 0);
              exchange.getResponseBody().write('{');
              exchange.getResponseBody().flush();
              for (int i = 0; i < 100; i++) {
                Thread.sleep(30);
                exchange.getResponseBody().write(' ');
                exchange.getResponseBody().flush();
              }
              return;
            }
            byte[] body;
            if (path.equals("/gzip")) {
              var buffer = new ByteArrayOutputStream();
              try (var gzip = new GZIPOutputStream(buffer)) {
                gzip.write(
                    ("{\"large\":\"" + "x".repeat(10000) + "\"}").getBytes(StandardCharsets.UTF_8));
              }
              body = buffer.toByteArray();
              exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            } else if (path.equals("/malformed"))
              body = "{broken response with untrusted values".getBytes(StandardCharsets.UTF_8);
            else
              body =
                  json.writeValueAsBytes(
                      Map.of(
                          "data",
                          Map.of("id", 42, "host", exchange.getRequestHeaders().getFirst("Host")),
                          "token",
                          "synthetic-response-token"));
            exchange.sendResponseHeaders(path.equals("/redirect") ? 302 : 200, body.length);
            exchange.getResponseBody().write(body);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          } catch (IOException ignored) {
            /* Expected when a deadline/cancellation aborts the fixture connection. */
          } finally {
            exchange.close();
          }
        });
    server.start();
    engine =
        engine(
            1024 * 1024,
            host -> {
              dnsCalls.incrementAndGet();
              return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            });
  }

  private SafeHttpExecutor engine(int bytes, SafeHttpExecutor.AddressResolver resolver) {
    return new SafeHttpExecutor(
        json,
        new TargetGuard(Set.of(3306, 6379, 8080)),
        bytes,
        3000,
        2,
        resolver,
        org.apache.hc.core5.ssl.SSLContexts.createSystemDefault());
  }

  private ExecutionSnapshot snapshot(String path, int timeout, boolean secret) {
    JsonNode config =
        json.valueToTree(
            Map.of(
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
                List.of(
                    Map.of("id", "status", "type", "STATUS_CODE", "expected", 200),
                    Map.of("id", "id", "type", "JSON_EQUALS", "path", "$.data.id", "expected", 42)),
                "extractors",
                List.of(),
                "timeoutMs",
                timeout));
    return new ExecutionSnapshot(
        1,
        101,
        1,
        1,
        201,
        0,
        "http://allowed.test:" + port,
        secret ? Map.of("token", "fixture-private-value") : Map.of(),
        secret ? Set.of("token") : Set.of(),
        List.of(new ExecutionSnapshot.TargetRule(1, "http", "allowed.test", port, "LOOPBACK")),
        List.of(new ExecutionSnapshot.Step(1, "HTTP fixture", 0, config)));
  }

  @AfterEach
  void stop() {
    engine.close();
    server.stop(0);
    fixtureThreads.shutdownNow();
  }

  @Test
  void actualConnectionUsesTheCheckedResolutionAndOriginalHost() {
    try (var pinned =
        engine(
            1048576,
            host -> {
              int lookup = dnsCalls.incrementAndGet();
              return new InetAddress[] {
                InetAddress.getByName(lookup == 1 ? "127.0.0.1" : "169.254.169.254")
              };
            })) {
      var result = pinned.execute(snapshot("/ok", 5000, false), new ExecutionControl(), () -> true);
      assertThat(result.state()).isEqualTo("PASSED");
      assertThat(dnsCalls.get()).isEqualTo(1);
      assertThat(json.valueToTree(result.report()).at("/response/body/data/host").asString())
          .isEqualTo("allowed.test:" + port);
      assertThat(json.writeValueAsString(result.report()))
          .doesNotContain("synthetic-response-token");
    }
  }

  @Test
  void redirectDoesNotContactItsDestination() {
    var result =
        engine.execute(snapshot("/redirect", 5000, false), new ExecutionControl(), () -> true);
    assertThat(result.state()).isEqualTo("FAILED");
    assertThat(followed.get()).isZero();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void automaticHttpRetriesAreDisabled() {
    var result = engine.execute(snapshot("/drop", 5000, false), new ExecutionControl(), () -> true);
    assertThat(result.state()).isEqualTo("ERROR");
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void timeoutBoundsAnActivelyStreamingResponse() {
    long start = System.nanoTime();
    var result = engine.execute(snapshot("/slow", 600, false), new ExecutionControl(), () -> true);
    assertThat(result.errorCode()).isEqualTo("REQUEST_TIMEOUT");
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2500);
  }

  @Test
  void gzipLimitAppliesAfterDecompressionAndCannotProduceAPassingTruncation() {
    try (var bounded =
        engine(256, host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")})) {
      var result =
          bounded.execute(snapshot("/gzip", 5000, false), new ExecutionControl(), () -> true);
      assertThat(result.state()).isEqualTo("ERROR");
      assertThat(result.errorCode()).isEqualTo("RESPONSE_TOO_LARGE");
      assertThat(result.bodyAvailable()).isFalse();
      assertThat(result.bodyTruncated()).isTrue();
    }
  }

  @Test
  void oversizedStreamsAreAbortedBeforeEntityCloseCanDrainThem() {
    try (var bounded =
        engine(256, host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")})) {
      long started = System.nanoTime();
      var result =
          bounded.execute(
              snapshot("/oversize-stream", 5000, false), new ExecutionControl(), () -> true);
      assertThat(result.errorCode()).isEqualTo("RESPONSE_TOO_LARGE");
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2000);
    }
  }

  @Test
  void responseHeadersCannotGrowWithoutABound() {
    var result =
        engine.execute(snapshot("/headers", 5000, false), new ExecutionControl(), () -> true);
    assertThat(result.state()).isEqualTo("ERROR");
    assertThat(result.bodyAvailable()).isFalse();
  }

  @Test
  void secretBearingRequestsWithholdResponseBodies() {
    var result = engine.execute(snapshot("/ok", 5000, true), new ExecutionControl(), () -> true);
    assertThat(result.state()).isEqualTo("PASSED");
    assertThat(result.bodyAvailable()).isFalse();
    assertThat(json.writeValueAsString(result.report()))
        .doesNotContain("fixture-private-value", "synthetic-response-token");
  }

  @Test
  void pathEncodingCannotBypassKnownSecretRedaction() {
    var original = snapshot("/${env.token}", 5000, true);
    String secret = "synthetic a/b";
    var input =
        new ExecutionSnapshot(
            1,
            101,
            1,
            1,
            201,
            0,
            original.baseUrl(),
            Map.of("token", secret),
            Set.of("token"),
            original.approvedTargets(),
            original.steps());
    var result = engine.execute(input, new ExecutionControl(), () -> true);
    assertThat(result.state()).isEqualTo("PASSED");
    String report = json.writeValueAsString(result.report());
    assertThat(report.contains(secret)).isFalse();
    assertThat(report.contains("synthetic%20a/b")).isFalse();
  }

  @Test
  void revocationAndForbiddenResolvedAddressesPreventTheRequest() {
    var denied = engine.execute(snapshot("/ok", 5000, false), new ExecutionControl(), () -> false);
    assertThat(denied.errorCode()).isEqualTo("AUTHORIZATION_REVOKED");
    assertThat(calls.get()).isZero();
    try (var unsafe =
        engine(1024, host -> new InetAddress[] {InetAddress.getByName("169.254.169.254")})) {
      assertThat(
              unsafe
                  .execute(snapshot("/ok", 5000, false), new ExecutionControl(), () -> true)
                  .errorCode())
          .isEqualTo("TARGET_ADDRESS_BLOCKED");
      assertThat(calls.get()).isZero();
    }
  }

  @Test
  void cancellationAbortsTheInFlightRequestWithoutInventingAResponse() throws Exception {
    var control = new ExecutionControl();
    try (var pool = Executors.newSingleThreadExecutor()) {
      var future =
          pool.submit(() -> engine.execute(snapshot("/slow", 5000, false), control, () -> true));
      for (int i = 0; i < 100 && calls.get() == 0; i++) Thread.sleep(10);
      assertThat(calls.get()).isEqualTo(1);
      control.cancel(ExecutionControl.Reason.USER_CANCELLED);
      var result = future.get(2, TimeUnit.SECONDS);
      assertThat(result.state()).isEqualTo("UNKNOWN");
      assertThat(result.bodyAvailable()).isFalse();
    }
  }

  @Test
  void malformedJsonDoesNotLeakResponseDataInErrors() {
    var result =
        engine.execute(snapshot("/malformed", 5000, false), new ExecutionControl(), () -> true);
    assertThat(result.errorCode()).isEqualTo("RESPONSE_JSON_INVALID");
    assertThat(json.writeValueAsString(result.report())).doesNotContain("untrusted values");
  }

  @Test
  void systemProxyPropertiesCannotRerouteAnAuthorizedConnection() {
    String oldHost = System.getProperty("http.proxyHost"),
        oldPort = System.getProperty("http.proxyPort");
    try {
      System.setProperty("http.proxyHost", "127.0.0.1");
      System.setProperty("http.proxyPort", "1");
      assertThat(
              engine
                  .execute(snapshot("/ok", 5000, false), new ExecutionControl(), () -> true)
                  .state())
          .isEqualTo("PASSED");
    } finally {
      if (oldHost == null) System.clearProperty("http.proxyHost");
      else System.setProperty("http.proxyHost", oldHost);
      if (oldPort == null) System.clearProperty("http.proxyPort");
      else System.setProperty("http.proxyPort", oldPort);
    }
  }

  @Test
  void pinnedTlsKeepsTrustAndOriginalHostnameVerification(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    String password = UUID.randomUUID().toString();
    var keystore = directory.resolve("ephemeral-fixture.p12");
    String keytool =
        java.nio.file.Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool")
            .toString();
    var generation =
        new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias",
                "fixture",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "1",
                "-dname",
                "CN=allowed.test",
                "-ext",
                "SAN=dns:allowed.test",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystore.toString(),
                "-storepass",
                password,
                "-noprompt")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (!generation.waitFor(20, TimeUnit.SECONDS)) {
      generation.destroyForcibly();
      fail("Ephemeral fixture certificate generation timed out");
    }
    assertThat(generation.exitValue()).isZero();
    var store = java.security.KeyStore.getInstance("PKCS12");
    try (var input = java.nio.file.Files.newInputStream(keystore)) {
      store.load(input, password.toCharArray());
    }
    var keys =
        javax.net.ssl.KeyManagerFactory.getInstance(
            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, password.toCharArray());
    var trust =
        javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    var ssl = javax.net.ssl.SSLContext.getInstance("TLS");
    ssl.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
    var secure = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    secure.setHttpsConfigurator(new HttpsConfigurator(ssl));
    secure.setExecutor(fixtureThreads);
    secure.createContext(
        "/ok",
        exchange -> {
          try {
            byte[] body = "{\"data\":{\"id\":42}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } finally {
            exchange.close();
          }
        });
    secure.start();
    int securePort = secure.getAddress().getPort();
    var original = snapshot("/ok", 5000, false);
    try (var trusted =
        new SafeHttpExecutor(
            json,
            new TargetGuard(Set.of(3306)),
            1048576,
            3000,
            2,
            host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")},
            ssl)) {
      var valid =
          new ExecutionSnapshot(
              1,
              101,
              1,
              1,
              201,
              0,
              "https://allowed.test:" + securePort,
              Map.of(),
              Set.of(),
              List.of(
                  new ExecutionSnapshot.TargetRule(
                      1, "https", "allowed.test", securePort, "LOOPBACK")),
              original.steps());
      assertThat(trusted.execute(valid, new ExecutionControl(), () -> true).state())
          .isEqualTo("PASSED");
      var wrongHost =
          new ExecutionSnapshot(
              1,
              101,
              1,
              1,
              201,
              0,
              "https://mismatch.test:" + securePort,
              Map.of(),
              Set.of(),
              List.of(
                  new ExecutionSnapshot.TargetRule(
                      1, "https", "mismatch.test", securePort, "LOOPBACK")),
              original.steps());
      assertThat(trusted.execute(wrongHost, new ExecutionControl(), () -> true).errorCode())
          .isEqualTo("TLS_ERROR");
      assertThat(engine.execute(valid, new ExecutionControl(), () -> true).errorCode())
          .isEqualTo("TLS_ERROR");
    } finally {
      secure.stop(0);
    }
  }
}
