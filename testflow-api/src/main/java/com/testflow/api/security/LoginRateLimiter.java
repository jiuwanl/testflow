package com.testflow.api.security;

import com.testflow.api.config.ApiProperties;
import com.testflow.api.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {
  private static final DefaultRedisScript<Long> COUNTER =
      new DefaultRedisScript<>(
          "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1])"
              + " end; return n",
          Long.class);
  private final StringRedisTemplate redis;
  private final String prefix;

  public LoginRateLimiter(StringRedisTemplate redis, ApiProperties properties) {
    this.redis = redis;
    this.prefix = properties.loginRatePrefix();
  }

  public void check(String username, String remoteAddress) {
    long perIp = increment(prefix + ":ip:" + digest(remoteAddress), 60);
    long perAccount = increment(prefix + ":account:" + digest(username + ":" + remoteAddress), 300);
    if (perIp > 60 || perAccount > 8) {
      throw new ApiException(429, "RATE_LIMITED", "登录尝试过于频繁，请稍后再试");
    }
  }

  private long increment(String key, int ttl) {
    Long result = redis.execute(COUNTER, List.of(key), Integer.toString(ttl));
    if (result == null) {
      throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "限流服务暂不可用");
    }
    return result;
  }

  private String digest(String input) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }
}
