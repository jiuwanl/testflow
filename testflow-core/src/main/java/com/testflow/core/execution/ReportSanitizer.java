package com.testflow.core.execution;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.hc.core5.http.Header;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Conservative display projection. A secret-bearing request never retains its response body. */
public final class ReportSanitizer {
  private static final Pattern SENSITIVE =
      Pattern.compile(
          "(?i).*(authorization|cookie|token|secret|password|credential|api[-_]?key).*");
  private final List<String> secretValues;
  private final JsonMapper json;

  public ReportSanitizer(JsonMapper json, ExecutionSnapshot snapshot) {
    this(json, snapshot, Map.of(), Set.of());
  }

  public ReportSanitizer(
      JsonMapper json,
      ExecutionSnapshot snapshot,
      Map<String, JsonNode> run,
      Set<String> runSecrets) {
    this.json = json;
    var values = new ArrayList<String>();
    for (String key : runSecrets) {
      var value = run.get(key);
      if (value != null) values.add(value.isTextual() ? value.asString() : value.toString());
    }
    secretValues =
        java.util.stream.Stream.concat(
                values.stream(), snapshot.secretNames().stream().map(snapshot.variables()::get))
            .filter(Objects::nonNull)
            .filter(value -> !value.isEmpty())
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
  }

  public boolean sensitive(String name) {
    return SENSITIVE.matcher(name).matches();
  }

  public boolean containsSensitive(JsonNode node) {
    if (node.isObject())
      return node.properties().stream()
          .anyMatch(entry -> sensitive(entry.getKey()) || containsSensitive(entry.getValue()));
    if (node.isArray()) {
      for (JsonNode value : node) if (containsSensitive(value)) return true;
    }
    return false;
  }

  public String scrub(String value) {
    String result = value;
    for (String secret : secretValues) {
      result = result.replace(secret, "[REDACTED]");
      String encoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
      result =
          result.replace(encoded, "[REDACTED]").replace(encoded.replace("+", "%20"), "[REDACTED]");
    }
    return result.length() > 4096 ? result.substring(0, 4096) + "…" : result;
  }

  public Object json(JsonNode value) {
    if (value.isObject()) {
      Map<String, Object> result = new LinkedHashMap<>();
      value
          .properties()
          .forEach(
              entry ->
                  result.put(
                      scrub(entry.getKey()),
                      sensitive(entry.getKey()) ? "[REDACTED]" : json(entry.getValue())));
      return result;
    }
    if (value.isArray()) {
      List<Object> result = new ArrayList<>();
      value.forEach(item -> result.add(json(item)));
      return result;
    }
    if (!value.isNull()
        && secretValues.contains(value.isTextual() ? value.asString() : value.toString()))
      return "[REDACTED]";
    return value.isTextual() ? scrub(value.asString()) : json.convertValue(value, Object.class);
  }

  public List<Map<String, String>> headers(Header[] headers, boolean withholdValues) {
    List<Map<String, String>> result = new ArrayList<>();
    for (Header header : headers) {
      if (result.size() >= 100) break;
      result.add(
          Map.of(
              "name",
              scrub(header.getName()),
              "value",
              withholdValues
                  ? "[WITHHELD]"
                  : sensitive(header.getName()) ? "[REDACTED]" : scrub(header.getValue())));
    }
    return result;
  }
}
