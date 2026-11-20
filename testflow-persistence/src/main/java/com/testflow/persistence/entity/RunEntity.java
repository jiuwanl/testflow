package com.testflow.persistence.entity;

import java.time.Instant;

public class RunEntity {
  private int maxConcurrency = 1;

  public int getMaxConcurrency() {
    return maxConcurrency;
  }

  public void setMaxConcurrency(int value) {
    maxConcurrency = value;
  }

  private String executionMode = "ORDERED";

  public String getExecutionMode() {
    return executionMode;
  }

  public void setExecutionMode(String value) {
    executionMode = value;
  }

  private Long id;
  private Long projectId;
  private Long requestedBy;
  private String idempotencyKey;
  private String requestHash;
  private String kind;
  private Long sourceId;
  private Long envId;
  private String sourceNameSnapshot;
  private String environmentNameSnapshot;
  private String requestedByName;
  private Long parentRunId;
  private String state;
  private String outcome;
  private String terminalReason;
  private String ownerId;
  private long leaseVersion;
  private Instant leaseUntil;
  private Instant heartbeatAt;
  private Instant createdAt;
  private Instant queuedAt;
  private Instant startedAt;
  private Instant finishedAt;
  private Instant deadlineAt;
  private int totalCount;
  private int passedCount;
  private int failedCount;
  private int errorCount;
  private int skippedCount;
  private int cancelledCount;
  private int unknownCount;
  private boolean leaseValid;
  private boolean deadlineExpired;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public Long getProjectId() {
    return projectId;
  }

  public void setProjectId(Long value) {
    this.projectId = value;
  }

  public Long getRequestedBy() {
    return requestedBy;
  }

  public void setRequestedBy(Long value) {
    this.requestedBy = value;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String value) {
    this.idempotencyKey = value;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public void setRequestHash(String value) {
    this.requestHash = value;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String value) {
    this.kind = value;
  }

  public Long getSourceId() {
    return sourceId;
  }

  public void setSourceId(Long value) {
    this.sourceId = value;
  }

  public Long getEnvId() {
    return envId;
  }

  public void setEnvId(Long value) {
    this.envId = value;
  }

  public String getSourceNameSnapshot() {
    return sourceNameSnapshot;
  }

  public void setSourceNameSnapshot(String value) {
    this.sourceNameSnapshot = value;
  }

  public String getEnvironmentNameSnapshot() {
    return environmentNameSnapshot;
  }

  public void setEnvironmentNameSnapshot(String value) {
    this.environmentNameSnapshot = value;
  }

  public String getRequestedByName() {
    return requestedByName;
  }

  public void setRequestedByName(String value) {
    this.requestedByName = value;
  }

  public Long getParentRunId() {
    return parentRunId;
  }

  public void setParentRunId(Long value) {
    this.parentRunId = value;
  }

  public String getState() {
    return state;
  }

  public void setState(String value) {
    this.state = value;
  }

  public String getOutcome() {
    return outcome;
  }

  public void setOutcome(String value) {
    this.outcome = value;
  }

  public String getTerminalReason() {
    return terminalReason;
  }

  public void setTerminalReason(String value) {
    this.terminalReason = value;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(String value) {
    this.ownerId = value;
  }

  public long getLeaseVersion() {
    return leaseVersion;
  }

  public void setLeaseVersion(long value) {
    this.leaseVersion = value;
  }

  public Instant getLeaseUntil() {
    return leaseUntil;
  }

  public void setLeaseUntil(Instant value) {
    this.leaseUntil = value;
  }

  public Instant getHeartbeatAt() {
    return heartbeatAt;
  }

  public void setHeartbeatAt(Instant value) {
    this.heartbeatAt = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    this.createdAt = value;
  }

  public Instant getQueuedAt() {
    return queuedAt;
  }

  public void setQueuedAt(Instant value) {
    this.queuedAt = value;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant value) {
    this.startedAt = value;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public void setFinishedAt(Instant value) {
    this.finishedAt = value;
  }

  public Instant getDeadlineAt() {
    return deadlineAt;
  }

  public void setDeadlineAt(Instant value) {
    this.deadlineAt = value;
  }

  public int getTotalCount() {
    return totalCount;
  }

  public void setTotalCount(int value) {
    this.totalCount = value;
  }

  public int getPassedCount() {
    return passedCount;
  }

  public void setPassedCount(int value) {
    this.passedCount = value;
  }

  public int getFailedCount() {
    return failedCount;
  }

  public void setFailedCount(int value) {
    this.failedCount = value;
  }

  public int getErrorCount() {
    return errorCount;
  }

  public void setErrorCount(int value) {
    this.errorCount = value;
  }

  public int getSkippedCount() {
    return skippedCount;
  }

  public void setSkippedCount(int value) {
    this.skippedCount = value;
  }

  public int getCancelledCount() {
    return cancelledCount;
  }

  public void setCancelledCount(int value) {
    this.cancelledCount = value;
  }

  public int getUnknownCount() {
    return unknownCount;
  }

  public void setUnknownCount(int value) {
    this.unknownCount = value;
  }

  public boolean isLeaseValid() {
    return leaseValid;
  }

  public void setLeaseValid(boolean value) {
    this.leaseValid = value;
  }

  public boolean isDeadlineExpired() {
    return deadlineExpired;
  }

  public void setDeadlineExpired(boolean value) {
    this.deadlineExpired = value;
  }
}
