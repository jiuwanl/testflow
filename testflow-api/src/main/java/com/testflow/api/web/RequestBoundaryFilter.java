package com.testflow.api.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBoundaryFilter extends OncePerRequestFilter {
  private static final long MAX_BODY = 1024 * 1024;
  private final ApiErrorWriter errors;

  public RequestBoundaryFilter(ApiErrorWriter errors) {
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    MDC.put("requestId", UUID.randomUUID().toString());
    response.setHeader("X-Request-Id", MDC.get("requestId"));
    response.setHeader("Cache-Control", "no-store");
    try {
      if (request.getContentLengthLong() > MAX_BODY) {
        errors.write(response, 413, "PAYLOAD_TOO_LARGE", "请求体超过限制");
        return;
      }
      chain.doFilter(
          new HttpServletRequestWrapper(request) {
            private ServletInputStream bounded;

            @Override
            public ServletInputStream getInputStream() throws IOException {
              if (bounded == null) {
                ServletInputStream source = super.getInputStream();
                bounded =
                    new ServletInputStream() {
                      private long read;

                      private void count(int n) throws IOException {
                        if (n > 0 && (read += n) > MAX_BODY) {
                          throw new PayloadLimitException();
                        }
                      }

                      @Override
                      public int read() throws IOException {
                        int value = source.read();
                        count(value < 0 ? 0 : 1);
                        return value;
                      }

                      @Override
                      public int read(byte[] b, int off, int len) throws IOException {
                        int n = source.read(b, off, len);
                        count(n);
                        return n;
                      }

                      @Override
                      public boolean isFinished() {
                        return source.isFinished();
                      }

                      @Override
                      public boolean isReady() {
                        return source.isReady();
                      }

                      @Override
                      public void setReadListener(ReadListener listener) {
                        source.setReadListener(listener);
                      }
                    };
              }
              return bounded;
            }

            @Override
            public BufferedReader getReader() throws IOException {
              return new BufferedReader(
                  new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            }
          },
          response);
    } catch (DataAccessException ex) {
      errors.write(response, 503, "DEPENDENCY_UNAVAILABLE", "数据服务暂不可用");
    } finally {
      MDC.remove("requestId");
    }
  }

  public static class PayloadLimitException extends IOException {
    public PayloadLimitException() {
      super("Request body limit exceeded");
    }
  }
}
