package com.testflow.core.execution;

import com.testflow.core.DefinitionValidator;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded synchronous execution. No database dependency and no automatic HTTP replay. */
public final class SafeHttpExecutor implements AutoCloseable {
  @FunctionalInterface
  public interface AddressResolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private final JsonMapper json;
  private final TargetGuard guard;
  private final int maxBodyBytes, connectTimeoutMs;
  private final AddressResolver resolver;
  private final SSLContext ssl;
  private final ThreadPoolExecutor dns;
  private final ScheduledThreadPoolExecutor deadlines;
  private final Semaphore requests;

  public SafeHttpExecutor(
      JsonMapper json, TargetGuard guard, int maxBodyBytes, int connectTimeoutMs, int maxRequests) {
    this(
        json,
        guard,
        maxBodyBytes,
        connectTimeoutMs,
        maxRequests,
        InetAddress::getAllByName,
        SSLContexts.createSystemDefault());
  }

  SafeHttpExecutor(
      JsonMapper json,
      TargetGuard guard,
      int maxBodyBytes,
      int connectTimeoutMs,
      int maxRequests,
      AddressResolver resolver,
      SSLContext ssl) {
    if (maxBodyBytes < 1
        || maxBodyBytes > 1024 * 1024
        || connectTimeoutMs < 1
        || connectTimeoutMs > 3000
        || maxRequests < 1
        || maxRequests > 4) throw new IllegalArgumentException("Invalid HTTP resource limits");
    this.json =
        JsonMapper.builder(
                tools.jackson.core.json.JsonFactory.builder()
                    .streamReadConstraints(
                        tools.jackson.core.StreamReadConstraints.builder()
                            .maxNestingDepth(64)
                            .maxStringLength(1024 * 1024)
                            .maxNumberLength(1000)
                            .build())
                    .build())
            .build();
    this.guard = guard;
    this.maxBodyBytes = maxBodyBytes;
    this.connectTimeoutMs = connectTimeoutMs;
    this.resolver = resolver;
    this.ssl = ssl;
    requests = new Semaphore(maxRequests);
    ThreadFactory threads =
        Thread.ofPlatform().daemon().name("testflow-http-control-", 0).factory();
    dns =
        new ThreadPoolExecutor(
            maxRequests,
            maxRequests,
            0,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            threads,
            new ThreadPoolExecutor.AbortPolicy());
    deadlines = new ScheduledThreadPoolExecutor(1, threads);
    deadlines.setRemoveOnCancelPolicy(true);
  }

  public ExecutionResult execute(
      ExecutionSnapshot snapshot, ExecutionControl control, BooleanSupplier permitted) {
    return execute(snapshot, 0, Map.of(), Set.of(), control, permitted);
  }

  public ExecutionResult execute(
      ExecutionSnapshot snapshot,
      int stepIndex,
      Map<String, JsonNode> runVariables,
      Set<String> runSecrets,
      ExecutionControl control,
      BooleanSupplier permitted) {
    long started = System.nanoTime();
    if (!requests.tryAcquire()) return ExecutionResult.error("HTTP_CAPACITY_EXHAUSTED", 0);
    ScheduledFuture<?> deadline = null;
    try {
      JsonNode config =
          new VariableTemplate(json)
              .bind(snapshot.steps().get(stepIndex).config(), snapshot.variables(), runVariables);
      DefinitionValidator.caseConfig(config);
      int timeout = config.path("timeoutMs").asInt(15000);
      long end = started + TimeUnit.MILLISECONDS.toNanos(timeout);
      deadline =
          deadlines.schedule(
              () -> control.cancel(ExecutionControl.Reason.REQUEST_TIMEOUT),
              Math.max(1, end - System.nanoTime()),
              TimeUnit.NANOSECONDS);
      ensure(control, permitted);
      URI uri = requestUri(snapshot.baseUrl(), config.path("request"));
      var rule = guard.authorize(uri, snapshot.approvedTargets());
      InetAddress[] pinned = resolve(rule.host(), end);
      guard.validate(rule, pinned);
      ensure(control, permitted);
      DnsResolver pinnedResolver =
          new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
              ensure(control, permitted);
              if (!DefinitionValidator.host(host).equals(rule.host()))
                throw new ExecutionFailure("TARGET_NOT_AUTHORIZED");
              return pinned.clone();
            }

            @Override
            public String resolveCanonicalHostname(String host) {
              return rule.host();
            }
          };
      var manager =
          PoolingHttpClientConnectionManagerBuilder.create()
              .setDnsResolver(pinnedResolver)
              .setConnectionFactory(
                  org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory.builder()
                      .http1Config(
                          org.apache.hc.core5.http.config.Http1Config.custom()
                              .setMaxHeaderCount(100)
                              .setMaxLineLength(8192)
                              .setMaxEmptyLineCount(10)
                              .build())
                      .build())
              .setMaxConnTotal(1)
              .setMaxConnPerRoute(1)
              .setTlsSocketStrategy(
                  ClientTlsStrategyBuilder.create().setSslContext(ssl).buildClassic())
              .setDefaultConnectionConfig(
                  ConnectionConfig.custom()
                      .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                      .setSocketTimeout(Timeout.ofMilliseconds(timeout))
                      .build())
              .build();
      try (var client =
          HttpClients.custom()
              .setConnectionManager(manager)
              .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
              .disableAutomaticRetries()
              .disableRedirectHandling()
              .disableCookieManagement()
              .disableContentCompression()
              .disableAuthCaching()
              .setConnectionReuseStrategy((request, response, context) -> false)
              .setDefaultRequestConfig(
                  RequestConfig.custom()
                      .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                      .setResponseTimeout(Timeout.ofMilliseconds(timeout))
                      .build())
              .build()) {
        JsonNode input = config.path("request");
        var request = new HttpUriRequestBase(input.path("method").asString(), uri);
        for (JsonNode header : input.path("headers"))
          request.addHeader(header.path("name").asString(), header.path("value").asString());
        if ("JSON".equals(input.path("body").path("type").asString())) {
          request.setEntity(
              new StringEntity(
                  json.writeValueAsString(input.path("body").path("json")),
                  ContentType.APPLICATION_JSON));
        }
        control.onAbort(request::cancel);
        ensure(control, permitted);
        var sanitizer = new ReportSanitizer(json, snapshot, runVariables, runSecrets);
        boolean secretBearing =
            !snapshot.secretNames().isEmpty()
                || !runSecrets.isEmpty()
                || sanitizer.containsSensitive(input.path("body"))
                || hasSensitiveParameters(input, sanitizer)
                || config
                    .path("extractors")
                    .valueStream()
                    .anyMatch(
                        value ->
                            value.path("secret").asBoolean()
                                || sanitizer.sensitive(value.path("name").asString())
                                || sanitizer.sensitive(value.path("path").asString()));
        Map<String, Object> requestView = requestView(input, uri, sanitizer);
        return client.execute(
            request,
            response -> {
              byte[] bytes =
                  readBody(
                      response.getEntity() == null ? null : response.getEntity().getContent(),
                      response.getFirstHeader("Content-Encoding") == null
                          ? "identity"
                          : response.getFirstHeader("Content-Encoding").getValue(),
                      control,
                      request::cancel);
              if (System.nanoTime() >= end) control.cancel(ExecutionControl.Reason.REQUEST_TIMEOUT);
              if (control.reason() != null) throw new ExecutionFailure(control.reason().name());
              long duration = elapsed(started);
              String contentType =
                  response.getFirstHeader("Content-Type") == null
                      ? ""
                      : response.getFirstHeader("Content-Type").getValue().toLowerCase(Locale.ROOT);
              JsonNode body = null;
              boolean isJson =
                  contentType.startsWith("application/json")
                      || contentType.split(";", 2)[0].endsWith("+json");
              if (isJson && bytes.length > 0) {
                try {
                  body = json.readTree(bytes);
                } catch (RuntimeException ex) {
                  throw new ExecutionFailure("RESPONSE_JSON_INVALID");
                }
              }
              List<Map<String, Object>> assertions =
                  evaluate(config, response.getCode(), response.getHeaders(), body, duration);
              boolean passed =
                  assertions.stream()
                      .allMatch(assertion -> Boolean.TRUE.equals(assertion.get("passed")));
              List<Map<String, Object>> extracted = new ArrayList<>();
              boolean extractionFailed = false;
              Map<String, JsonNode> outputs = new LinkedHashMap<>();
              Set<String> outputSecrets = new HashSet<>();
              for (JsonNode extractor : config.path("extractors")) {
                JsonNode value = JsonFieldPath.read(body, extractor.path("path").asString());
                boolean success = value != null && value.isValueNode() && !value.isNull();
                success =
                    success
                        && value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            <= 4096;
                extractionFailed |= !success;
                String outputName = extractor.path("name").asString();
                if (success) {
                  outputs.put(outputName, value.deepCopy());
                  if (secretBearing
                      || extractor.path("secret").asBoolean()
                      || sanitizer.sensitive(outputName)
                      || sanitizer.sensitive(extractor.path("path").asString()))
                    outputSecrets.add(outputName);
                }
                extracted.add(
                    Map.of(
                        "name",
                        extractor.path("name").asString(),
                        "secret",
                        extractor.path("secret").asBoolean() || outputSecrets.contains(outputName),
                        "success",
                        success));
              }
              var projected = new HashMap<String, JsonNode>(runVariables);
              projected.putAll(outputs);
              if (projected.size() > 1000
                  || projected.entrySet().stream()
                          .mapToLong(
                              e ->
                                  e.getKey().length()
                                      + e.getValue()
                                          .toString()
                                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                          .length)
                          .sum()
                      > 4 * 1024 * 1024) throw new ExecutionFailure("RUN_VARIABLE_LIMIT");
              if (System.nanoTime() >= end) control.cancel(ExecutionControl.Reason.REQUEST_TIMEOUT);
              if (control.reason() != null) throw new ExecutionFailure(control.reason().name());
              boolean available = isJson && body != null && !secretBearing;
              Map<String, Object> responseView = new LinkedHashMap<>();
              responseView.put("statusCode", response.getCode());
              responseView.put("durationMs", duration);
              responseView.put("bodyBytes", bytes.length);
              responseView.put("headers", sanitizer.headers(response.getHeaders(), secretBearing));
              responseView.put("bodyAvailable", available);
              responseView.put(
                  "bodyUnavailableReason",
                  available
                      ? null
                      : secretBearing ? "SENSITIVE_REQUEST" : "BODY_NOT_SAFELY_DISPLAYABLE");
              responseView.put("body", available ? sanitizer.json(body) : null);
              String error = extractionFailed ? "EXTRACTION_FAILED" : null;
              Map<String, Object> report = new LinkedHashMap<>();
              report.put(
                  "configurationVersions",
                  Map.of(
                      "caseVersion",
                      snapshot.steps().get(stepIndex).version(),
                      "environmentVersion",
                      snapshot.environmentVersion()));
              report.put("request", requestView);
              report.put("response", responseView);
              report.put("assertions", assertions);
              report.put("extractors", extracted);
              report.put("errorCode", error);
              if (System.nanoTime() >= end) control.cancel(ExecutionControl.Reason.REQUEST_TIMEOUT);
              if (control.reason() != null) throw new ExecutionFailure(control.reason().name());
              return new ExecutionResult(
                  extractionFailed ? "ERROR" : passed ? "PASSED" : "FAILED",
                  error,
                  duration,
                  report,
                  available,
                  false,
                  passed && !extractionFailed ? outputs : Map.of(),
                  passed && !extractionFailed ? outputSecrets : Set.of());
            });
      }
    } catch (Exception ex) {
      String code =
          ex instanceof ExecutionFailure failure
              ? failure.code()
              : ex instanceof javax.net.ssl.SSLException
                  ? "TLS_ERROR"
                  : ex instanceof SocketTimeoutException
                      ? "REQUEST_TIMEOUT"
                      : ex instanceof UnknownHostException
                          ? "DNS_FAILED"
                          : ex instanceof IOException ? "NETWORK_ERROR" : "CONFIGURATION_ERROR";
      if (control.reason() != null) code = control.reason().name();
      if (Set.of("USER_CANCELLED", "RUN_DEADLINE", "LEASE_LOST", "WORKER_SHUTDOWN")
          .contains(code)) {
        return new ExecutionResult(
            "UNKNOWN",
            code,
            elapsed(started),
            Map.of("errorCode", code, "bodyAvailable", false),
            false,
            false);
      }
      return new ExecutionResult(
          "ERROR",
          code,
          elapsed(started),
          Map.of("errorCode", code, "bodyAvailable", false),
          false,
          "RESPONSE_TOO_LARGE".equals(code));
    } finally {
      if (deadline != null) deadline.cancel(false);
      control.clearAbort();
      requests.release();
    }
  }

  private void ensure(ExecutionControl control, BooleanSupplier permitted) {
    if (control.reason() != null) throw new ExecutionFailure(control.reason().name());
    if (!permitted.getAsBoolean()) throw new ExecutionFailure("AUTHORIZATION_REVOKED");
  }

  private InetAddress[] resolve(String host, long end) throws Exception {
    if (org.apache.hc.core5.net.InetAddressUtils.isIPv4Address(host)
        || org.apache.hc.core5.net.InetAddressUtils.isIPv6Address(host)) {
      return new InetAddress[] {InetAddress.getByName(host)};
    }
    Future<InetAddress[]> lookup;
    try {
      lookup = dns.submit(() -> resolver.resolve(host));
    } catch (RejectedExecutionException ex) {
      throw new ExecutionFailure("DNS_CAPACITY_EXHAUSTED");
    }
    try {
      return lookup.get(
          Math.max(
              1,
              Math.min(connectTimeoutMs, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()))),
          TimeUnit.MILLISECONDS);
    } catch (TimeoutException ex) {
      throw new ExecutionFailure("DNS_TIMEOUT");
    } catch (java.util.concurrent.ExecutionException ex) {
      if (ex.getCause() instanceof UnknownHostException) throw new ExecutionFailure("DNS_FAILED");
      throw new ExecutionFailure("DNS_INVALID");
    } finally {
      lookup.cancel(true);
    }
  }

  private URI requestUri(String baseUrl, JsonNode request) throws URISyntaxException {
    URI base = URI.create(baseUrl);
    String prefix = base.getPath() == null ? "" : base.getPath().replaceFirst("/$", "");
    URIBuilder builder = new URIBuilder(base).setPath(prefix + request.path("path").asString());
    for (JsonNode query : request.path("query"))
      builder.addParameter(query.path("name").asString(), query.path("value").asString());
    return builder.build();
  }

  private byte[] readBody(
      InputStream source, String encoding, ExecutionControl control, Runnable abort)
      throws IOException {
    if (source == null) return new byte[0];
    InputStream wire =
        new FilterInputStream(source) {
          private int count;

          private void add(int length) {
            if (length > 0 && (count += length) > maxBodyBytes)
              throw new ExecutionFailure("RESPONSE_TOO_LARGE");
          }

          @Override
          public int read() throws IOException {
            int value = super.read();
            add(value < 0 ? 0 : 1);
            return value;
          }

          @Override
          public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length);
            add(count);
            return count;
          }
        };
    InputStream decoded;
    try {
      decoded =
          switch (encoding.trim().toLowerCase(Locale.ROOT)) {
            case "identity", "" -> wire;
            case "gzip" -> new GZIPInputStream(wire);
            case "deflate" -> new InflaterInputStream(wire);
            default -> throw new ExecutionFailure("RESPONSE_ENCODING_UNSUPPORTED");
          };
    } catch (IOException | RuntimeException ex) {
      abort.run();
      throw ex;
    }
    try (decoded;
        var output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int length;
      try {
        while ((length = decoded.read(buffer)) != -1) {
          if (control.reason() != null) throw new ExecutionFailure(control.reason().name());
          if (output.size() + length > maxBodyBytes)
            throw new ExecutionFailure("RESPONSE_TOO_LARGE");
          output.write(buffer, 0, length);
        }
      } catch (IOException | RuntimeException ex) {
        // Abort before closing the entity stream; closing a reusable stream can otherwise drain it.
        abort.run();
        throw ex;
      }
      return output.toByteArray();
    }
  }

  private boolean hasSensitiveParameters(JsonNode request, ReportSanitizer sanitizer) {
    for (String group : List.of("headers", "query"))
      for (JsonNode value : request.path(group))
        if (sanitizer.sensitive(value.path("name").asString())) return true;
    return false;
  }

  private Map<String, Object> requestView(JsonNode input, URI uri, ReportSanitizer sanitizer) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("method", input.path("method").asString());
    // Query values are projected separately so sensitive names can be masked even without a secret
    // variable.
    String authority = uri.getScheme() + "://" + uri.getRawAuthority() + uri.getPath();
    view.put("url", sanitizer.scrub(authority));
    for (String group : List.of("query", "headers")) {
      List<Map<String, String>> values = new ArrayList<>();
      for (JsonNode item : input.path(group))
        values.add(
            Map.of(
                "name",
                sanitizer.scrub(item.path("name").asString()),
                "value",
                sanitizer.sensitive(item.path("name").asString())
                    ? "[REDACTED]"
                    : sanitizer.scrub(item.path("value").asString())));
      view.put(group, values);
    }
    view.put("body", sanitizer.json(input.path("body")));
    return view;
  }

  private List<Map<String, Object>> evaluate(
      JsonNode config,
      int status,
      org.apache.hc.core5.http.Header[] headers,
      JsonNode body,
      long duration) {
    List<Map<String, Object>> results = new ArrayList<>();
    for (JsonNode assertion : config.path("assertions")) {
      String type = assertion.path("type").asString();
      boolean passed =
          switch (type) {
            case "STATUS_CODE" -> status == assertion.path("expected").asInt();
            case "DURATION_LT" -> duration < assertion.path("expected").asLong();
            case "HEADER" ->
                Arrays.stream(headers)
                    .anyMatch(
                        header ->
                            header.getName().equalsIgnoreCase(assertion.path("name").asString())
                                && ("EXISTS".equals(assertion.path("operator").asString())
                                    || header
                                        .getValue()
                                        .equals(assertion.path("expected").asString())));
            case "JSON_EXISTS" ->
                JsonFieldPath.read(body, assertion.path("path").asString()) != null;
            case "JSON_EQUALS" ->
                Objects.equals(
                    JsonFieldPath.read(body, assertion.path("path").asString()),
                    assertion.get("expected"));
            default -> throw new ExecutionFailure("ASSERTION_INVALID");
          };
      results.add(Map.of("id", assertion.path("id").asString(), "type", type, "passed", passed));
    }
    return results;
  }

  private static long elapsed(long started) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
  }

  @Override
  public void close() {
    deadlines.shutdownNow();
    dns.shutdownNow();
  }
}
