package com.testflow.persistence.entity;

public class ProjectEntity extends BaseEntity {
  private String name;
  private String status;
  private String currentRole;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getCurrentRole() {
    return currentRole;
  }

  public void setCurrentRole(String currentRole) {
    this.currentRole = currentRole;
  }
}
