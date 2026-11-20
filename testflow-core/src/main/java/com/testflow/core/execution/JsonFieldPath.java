package com.testflow.core.execution;

import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

public final class JsonFieldPath {
  private static final Pattern TOKEN = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_]*)|\\[([0-9]+)]");

  private JsonFieldPath() {}

  public static JsonNode read(JsonNode root, String path) {
    if (root == null) return null;
    var matcher = TOKEN.matcher(path.substring(1));
    JsonNode node = root;
    while (matcher.find() && node != null) {
      node =
          matcher.group(1) != null
              ? node.get(matcher.group(1))
              : node.get(Integer.parseInt(matcher.group(2)));
    }
    return node;
  }
}
