package com.testflow.persistence.entity;

public class UserEntity extends BaseEntity {
  private String username;
  private String passwordHash;
  private boolean globalAdmin;
  private String status;

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public boolean isGlobalAdmin() {
    return globalAdmin;
  }

  public void setGlobalAdmin(boolean globalAdmin) {
    this.globalAdmin = globalAdmin;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}
