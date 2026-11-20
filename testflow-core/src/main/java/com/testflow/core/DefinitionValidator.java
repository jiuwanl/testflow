package com.testflow.core;

import java.net.IDN;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Validation of stored definitions only. DNS/connection authorization belongs to stage 2. */
public final class DefinitionValidator {
  private static final Set<String> METHODS =
      Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
  private static final Set<String> BLOCKED_HEADERS =
      Set.of(
          "host",
          "content-length",
          "connection",
          "proxy-authorization",
          "proxy-connection",
          "transfer-encoding",
          "upgrade",
          "te",
          "trailer",
          "expect");
  private static final Set<String> ASSERTIONS =
      Set.of("STATUS_CODE", "HEADER", "JSON_EXISTS", "JSON_EQUALS", "DURATION_LT");

  private DefinitionValidator() {}

  public static String baseUrl(String input) {
    try {
      URI uri = URI.create(input);
      if (!Set.of("http", "https").contains(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || uri.getQuery() != null
          || uri.getPort() == 0
          || uri.getPort() > 65535
          || input.contains("\\")) {
        throw new IllegalArgumentException();
      }
      return uri.normalize().toASCIIString();
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("baseUrl 必须为无账号、查询参数和片段的 HTTP/HTTPS 地址");
    }
  }

  public static String host(String input) {
    try {
      if (input == null
          || input.isBlank()
          || input.contains("*")
          || input.contains("/")
          || input.contains("@")) {
        throw new IllegalArgumentException();
      }
      String host = input.toLowerCase(Locale.ROOT);
      if (host.endsWith(".")) {
        host = host.substring(0, host.length() - 1);
      }
      if (host.startsWith("[") && host.endsWith("]")) {
        host = host.substring(1, host.length() - 1);
      }
      URI parsed =
          URI.create(
              "http://" + (host.contains(":") ? "[" + host + "]" : IDN.toASCII(host)) + ":80");
      if (parsed.getHost() == null
          || parsed.getRawUserInfo() != null
          || !parsed.getRawPath().isEmpty()
          || host.contains("%")
          || host.length() > 253) {
        throw new IllegalArgumentException();
      }
      return host.contains(":") ? host : IDN.toASCII(host);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("目标主机必须是精确域名或 IP，不能包含通配符、路径或账号");
    }
  }

  public static void caseConfig(JsonNode config) {
    require(config != null && config.isObject(), "config 必须为对象");
    boundedDepth(config, 0);
    JsonNode request = config.path("request");
    require(request.isObject(), "config.request 必须为对象");
    require(METHODS.contains(request.path("method").asString()), "不支持的 HTTP 方法");
    String path = request.path("path").asString();
    require(
        path.startsWith("/")
            && !path.startsWith("//")
            && !path.contains("\\")
            && !path.contains("#")
            && !path.contains("\r")
            && !path.contains("\n")
            && path.length() <= 2048,
        "请求路径必须是以单个 / 开始的相对路径");
    parameters(request.path("query"), false);
    parameters(request.path("headers"), true);
    JsonNode body = request.path("body");
    require(
        body.isObject() && Set.of("NONE", "JSON").contains(body.path("type").asString()),
        "正文类型仅支持 NONE/JSON");
    require(!"JSON".equals(body.path("type").asString()) || body.has("json"), "JSON 正文缺少 json 字段");
    if (config.has("timeoutMs")) {
      require(
          config.path("timeoutMs").isIntegralNumber()
              && config.path("timeoutMs").asInt() >= 1
              && config.path("timeoutMs").asInt() <= 60000,
          "timeoutMs 必须在 1～60000 内");
    }
    JsonNode assertions = config.path("assertions");
    require(assertions.isArray() && assertions.size() <= 100, "assertions 必须为最多 100 项的数组");
    Set<String> ids = new HashSet<>();
    for (JsonNode assertion : assertions) {
      String id = assertion.path("id").asString();
      String type = assertion.path("type").asString();
      require(id.matches("[A-Za-z0-9_-]{1,64}") && ids.add(id), "断言 ID 无效或重复");
      require(ASSERTIONS.contains(type), "不支持的断言类型");
      if (type.startsWith("JSON_")) {
        jsonPath(assertion.path("path").asString());
      }
      if (Set.of("JSON_EQUALS", "STATUS_CODE", "DURATION_LT").contains(type)) {
        require(
            assertion.has("expected") && assertion.path("expected").isValueNode(),
            "断言缺少标量 expected");
      }
      if ("STATUS_CODE".equals(type)) {
        require(
            assertion.path("expected").isIntegralNumber()
                && assertion.path("expected").asInt() >= 100
                && assertion.path("expected").asInt() <= 599,
            "状态码断言范围为 100～599");
      }
      if ("DURATION_LT".equals(type)) {
        require(
            assertion.path("expected").isIntegralNumber()
                && assertion.path("expected").asInt() > 0
                && assertion.path("expected").asInt() <= 60000,
            "耗时断言上限为 60000ms");
      }
      if ("HEADER".equals(type)) {
        require(
            assertion.path("name").asString().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}"),
            "响应头断言名称无效");
        require(
            Set.of("EXISTS", "EQUALS").contains(assertion.path("operator").asString()),
            "响应头断言 operator 无效");
        require(
            !"EQUALS".equals(assertion.path("operator").asString())
                || assertion.path("expected").isTextual(),
            "响应头等值断言缺少 expected");
      }
    }
    JsonNode extractors = config.path("extractors");
    require(extractors.isArray() && extractors.size() <= 100, "extractors 必须为最多 100 项的数组");
    Set<String> variables = new HashSet<>();
    for (JsonNode extractor : extractors) {
      String name = extractor.path("name").asString();
      require(name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") && variables.add(name), "提取变量名无效或重复");
      jsonPath(extractor.path("path").asString());
      require(extractor.path("secret").isBoolean(), "提取器必须显式设置 secret");
    }
  }

  private static void parameters(JsonNode values, boolean headers) {
    require(values.isArray() && values.size() <= 128, "query/headers 必须为最多 128 项的数组");
    for (JsonNode value : values) {
      String name = value.path("name").asString();
      require(
          !name.isBlank()
              && name.length() <= 128
              && value.path("value").isTextual()
              && value.path("value").asString().length() <= 4096,
          "请求参数格式无效");
      require(
          !name.contains("\r")
              && !name.contains("\n")
              && !value.path("value").asString().contains("\r")
              && !value.path("value").asString().contains("\n"),
          "请求参数不能包含换行");
      if (headers) {
        require(
            name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                && !BLOCKED_HEADERS.contains(name.toLowerCase(Locale.ROOT)),
            "请求头名称无效或由客户端受控管理");
      }
    }
  }

  private static void jsonPath(String path) {
    require(
        path.length() <= 256
            && path.matches("\\$(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[(?:0|[1-9][0-9]{0,5})\\])+"),
        "JSON 路径只支持字段名与数组下标");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  private static void boundedDepth(JsonNode node, int depth) {
    require(depth <= 64, "请求配置嵌套不能超过 64 层");
    if (node.isContainer()) for (JsonNode child : node) boundedDepth(child, depth + 1);
  }
}
