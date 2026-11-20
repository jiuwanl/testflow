package com.testflow.persistence.entity;

public class EnvironmentEntity extends BaseEntity {
  private Long projectId;
  private String name;
  private String baseUrl;
  private String variablesJson;
  private String secretCiphertext;
  private String keyVersion;
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

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getVariablesJson() {
    return variablesJson;
  }

  public void setVariablesJson(String variablesJson) {
    this.variablesJson = variablesJson;
  }

  public String getSecretCiphertext() {
    return secretCiphertext;
  }

  public void setSecretCiphertext(String secretCiphertext) {
    this.secretCiphertext = secretCiphertext;
  }

  public String getKeyVersion() {
    return keyVersion;
  }

  public void setKeyVersion(String keyVersion) {
    this.keyVersion = keyVersion;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}
