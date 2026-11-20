package com.testflow.persistence.entity;

public class TestPlanEntity extends BaseEntity {
  private int maxConcurrency = 1;

  public int getMaxConcurrency() {
    return maxConcurrency;
  }

  public void setMaxConcurrency(int value) {
    maxConcurrency = value;
  }

  private Long projectId;
  private String name;
  private String executionMode;
  private boolean failFast;
  private String status;

  public Long getProjectId() {
    return projectId;
  }

  public void setProjectId(Long projectId) {
    this.projectId = projectId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getExecutionMode() {
    return executionMode;
  }

  public void setExecutionMode(String executionMode) {
    this.executionMode = executionMode;
  }

  public boolean isFailFast() {
    return failFast;
  }

  public void setFailFast(boolean failFast) {
    this.failFast = failFast;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}
