package com.testflow.api.service;

import com.testflow.persistence.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RunMaintenance {
  private static final Logger LOG = LoggerFactory.getLogger(RunMaintenance.class);
  private final RunStore store;

  public RunMaintenance(RunStore store) {
    this.store = store;
  }

  @Scheduled(
      fixedDelayString = "${testflow.execution.recovery-interval-ms:5000}",
      initialDelayString = "${testflow.execution.recovery-interval-ms:5000}")
  public void recover() {
    try {
      store.recover(50);
    } catch (RuntimeException ex) {
      LOG.warn("Run maintenance unavailable type={}", ex.getClass().getSimpleName());
    }
  }
}
