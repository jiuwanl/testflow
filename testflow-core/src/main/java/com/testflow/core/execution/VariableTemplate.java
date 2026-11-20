package com.testflow.core.execution;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** String-valued environment bindings, applied as JSON nodes rather than JSON source fragments. */
public final class VariableTemplate {
  private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^{}]*)}");
  private final JsonMapper json;

  public VariableTemplate(JsonMapper json) {
    this.json = json;
  }

  public String bind(String text, Map<String, String> variables) {
    return bind(text, variables, Map.of());
  }

  public String bind(String text, Map<String, String> variables, Map<String, JsonNode> run) {
    Matcher matcher = REFERENCE.matcher(text);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String reference = matcher.group(1);
      if (!reference.matches("(?:env|run)\\.[A-Za-z_][A-Za-z0-9_]{0,63}"))
        throw new ExecutionFailure("VARIABLE_REFERENCE_INVALID");
      String value =
          reference.startsWith("env.")
              ? variables.get(reference.substring(4))
              : scalar(run.get(reference.substring(4)));
      if (value == null) throw new ExecutionFailure("VARIABLE_NOT_FOUND");
      matcher.appendReplacement(result, Matcher.quoteReplacement(value));
      if (result.length() > 4 * 1024 * 1024) throw new ExecutionFailure("TEMPLATE_TOO_LARGE");
    }
    matcher.appendTail(result);
    if (result.length() > 4 * 1024 * 1024) throw new ExecutionFailure("TEMPLATE_TOO_LARGE");
    // Only the template is inspected for malformed references; bound values are opaque strings.
    if (REFERENCE.matcher(text).replaceAll("").contains("${"))
      throw new ExecutionFailure("VARIABLE_REFERENCE_INVALID");
    return result.toString();
  }

  public JsonNode bind(JsonNode node, Map<String, String> variables) {
    return bind(node, variables, Map.of());
  }

  public JsonNode bind(JsonNode node, Map<String, String> variables, Map<String, JsonNode> run) {
    return bind(node, variables, run, new long[1], 0, false);
  }

  private static String scalar(JsonNode value) {
    return value == null ? null : value.isTextual() ? value.asString() : value.toString();
  }

  private JsonNode bind(
      JsonNode node,
      Map<String, String> variables,
      Map<String, JsonNode> run,
      long[] bytes,
      int depth,
      boolean typed) {
    if (depth > 64) throw new ExecutionFailure("TEMPLATE_TOO_DEEP");
    if (node.isTextual()) {
      String text = node.asString();
      if (typed && text.matches("\\$\\{run\\.[A-Za-z_][A-Za-z0-9_]{0,63}}")) {
        JsonNode value = run.get(text.substring(6, text.length() - 1));
        if (value == null) throw new ExecutionFailure("VARIABLE_NOT_FOUND");
        bytes[0] += value.toString().length();
        if (bytes[0] > 4 * 1024 * 1024) throw new ExecutionFailure("TEMPLATE_TOO_LARGE");
        return value.deepCopy();
      }
      String value = bind(text, variables, run);
      bytes[0] += value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      if (bytes[0] > 4 * 1024 * 1024) throw new ExecutionFailure("TEMPLATE_TOO_LARGE");
      return json.getNodeFactory().stringNode(value);
    }
    if (node.isObject()) {
      ObjectNode result = json.createObjectNode();
      for (var field : node.properties()) {
        if (field.getKey().contains("${")) throw new ExecutionFailure("VARIABLE_KEY_NOT_SUPPORTED");
        result.set(
            field.getKey(),
            bind(
                field.getValue(),
                variables,
                run,
                bytes,
                depth + 1,
                typed || field.getKey().equals("json") || field.getKey().equals("expected")));
      }
      return result;
    }
    if (node.isArray()) {
      ArrayNode result = json.createArrayNode();
      node.forEach(value -> result.add(bind(value, variables, run, bytes, depth + 1, typed)));
      return result;
    }
    return node.deepCopy();
  }
}
