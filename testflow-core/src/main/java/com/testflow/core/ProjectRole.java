package com.testflow.core;

public enum ProjectRole {
  VIEWER,
  MAINTAINER,
  OWNER;

  public boolean includes(ProjectRole required) {
    return ordinal() >= required.ordinal();
  }
}
