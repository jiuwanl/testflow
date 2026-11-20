package com.testflow.api.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ApiErrorWriter {
  private final JsonMapper json;

  public ApiErrorWriter(JsonMapper json) {
    this.json = json;
  }

  public Map<String, Object> body(String code, String message, List<?> fields) {
    return Map.of(
        "error",
        Map.of("code", code, "message", message, "fieldErrors", fields),
        "requestId",
        String.valueOf(MDC.get("requestId")));
  }

  public void write(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.resetBuffer();
    response.setStatus(status);
    response.setCharacterEncoding("UTF-8");
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    response.getWriter().write(json.writeValueAsString(body(code, message, List.of())));
  }
}
