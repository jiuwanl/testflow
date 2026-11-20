package com.testflow.api.web;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long total) {
  public static int offset(int page, int size) {
    if (page < 1 || page > 100000 || size < 1 || size > 100) {
      throw ApiException.invalid("page 范围 1～100000，size 范围 1～100");
    }
    return (page - 1) * size;
  }
}
