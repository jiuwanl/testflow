package com.testflow.persistence.entity;

public class TargetPolicyEntity extends BaseEntity {
  private Long projectId;
  private String scheme;
  private String host;
  private int port;
  private String addressScope;
  private boolean enabled;

  public Long getProjectId() {
    return projectId;
  }

  public void setProjectId(Long projectId) {
    this.projectId = projectId;
  }

  public String getScheme() {
    return scheme;
  }

  public void setScheme(String scheme) {
    this.scheme = scheme;
  }

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getAddressScope() {
    return addressScope;
  }

  public void setAddressScope(String addressScope) {
    this.addressScope = addressScope;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
