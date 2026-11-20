package com.testflow.api.security;

import java.io.Serializable;

public record AccountPrincipal(long id, long version) implements Serializable {
  private static final long serialVersionUID = 1L;
}
