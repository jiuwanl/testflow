package com.testflow.api.service;

import com.testflow.api.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

public final class ServiceSupport {
  private ServiceSupport() {}

  public static long id(String value) {
    try {
      long id = Long.parseLong(value);
      if (id <= 0) {
        throw new NumberFormatException();
      }
      return id;
    } catch (NumberFormatException ex) {
      throw ApiException.invalid("ID 必须为正整数字符串");
    }
  }

  public static String username(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }

  public static void password(String value) {
    if (value == null
        || value.length() < 12
        || value.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw ApiException.invalid("密码至少 12 字符，UTF-8 编码不超过 72 字节");
    }
  }

  public static String name(String value) {
    String name = value.trim();
    if (name.isBlank() || name.length() > 100) {
      throw ApiException.invalid("名称须为 1～100 字符");
    }
    return name;
  }

  public static String filterStatus(String value) {
    if (value != null && !Set.of("ACTIVE", "ARCHIVED").contains(value)) {
      throw ApiException.invalid("状态筛选无效");
    }
    return value;
  }

  public static String filterName(String value) {
    if (value != null && value.length() > 100) {
      throw ApiException.invalid("名称筛选过长");
    }
    return value;
  }

  public static void changed(int count) {
    if (count != 1) {
      throw ApiException.conflict();
    }
  }
}
