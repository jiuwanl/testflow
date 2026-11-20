package com.testflow.persistence.entity;

public class ApiCaseEntity extends BaseEntity {
  private Long projectId;
  private String name;
  private String tagsJson;
  private String priority;
  private int configSchemaVersion;
  private String configJson;
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

  public String getTagsJson() {
    return tagsJson;
  }

  public void setTagsJson(String tagsJson) {
    this.tagsJson = tagsJson;
  }

  public String getPriority() {
    return priority;
  }

  public void setPriority(String priority) {
    this.priority = priority;
  }

  public int getConfigSchemaVersion() {
    return configSchemaVersion;
  }

  public void setConfigSchemaVersion(int configSchemaVersion) {
    this.configSchemaVersion = configSchemaVersion;
  }

  public String getConfigJson() {
    return configJson;
  }

  public void setConfigJson(String configJson) {
    this.configJson = configJson;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}
