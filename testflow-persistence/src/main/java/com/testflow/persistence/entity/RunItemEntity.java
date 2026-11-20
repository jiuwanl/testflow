package com.testflow.persistence.entity;

import java.time.Instant;

public class RunItemEntity {
  private Long id;
  private Long projectId;
  private Long runId;
  private int ordinal;
  private Long sourceCaseId;
  private String caseNameSnapshot;
  private String state;
  private Instant startedAt;
  private Instant finishedAt;
  private Long durationMs;
  private String errorCode;
  private String reportJson;
  private boolean bodyAvailable;
  private boolean bodyTruncated;

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

  public Long getRunId() {
    return runId;
  }

  public void setRunId(Long value) {
    this.runId = value;
  }

  public int getOrdinal() {
    return ordinal;
  }

  public void setOrdinal(int value) {
    this.ordinal = value;
  }

  public Long getSourceCaseId() {
    return sourceCaseId;
  }

  public void setSourceCaseId(Long value) {
    this.sourceCaseId = value;
  }

  public String getCaseNameSnapshot() {
    return caseNameSnapshot;
  }

  public void setCaseNameSnapshot(String value) {
    this.caseNameSnapshot = value;
  }

  public String getState() {
    return state;
  }

  public void setState(String value) {
    this.state = value;
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

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long value) {
    this.durationMs = value;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(String value) {
    this.errorCode = value;
  }

  public String getReportJson() {
    return reportJson;
  }

  public void setReportJson(String value) {
    this.reportJson = value;
  }

  public boolean isBodyAvailable() {
    return bodyAvailable;
  }

  public void setBodyAvailable(boolean value) {
    this.bodyAvailable = value;
  }

  public boolean isBodyTruncated() {
    return bodyTruncated;
  }

  public void setBodyTruncated(boolean value) {
    this.bodyTruncated = value;
  }
}
