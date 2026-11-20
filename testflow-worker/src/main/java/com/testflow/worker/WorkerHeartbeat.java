package com.testflow.worker;

import com.testflow.persistence.SchemaVersion;
import com.testflow.persistence.mapper.WorkerMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkerHeartbeat implements ApplicationRunner {
  private static final Logger LOG = LoggerFactory.getLogger(WorkerHeartbeat.class);
  private final String workerId = UUID.randomUUID().toString();
  private final AtomicBoolean registered = new AtomicBoolean();
  private final AtomicBoolean initialized = new AtomicBoolean();
  private final SchemaGuard schema;
  private final WorkerMapper mapper;

  public WorkerHeartbeat(SchemaGuard schema, WorkerMapper mapper) {
    this.schema = schema;
    this.mapper = mapper;
  }

  @Override
  public void run(ApplicationArguments args) {
    schema.verify();
    mapper.register(workerId, "0.1.0-SNAPSHOT", SchemaVersion.EXPECTED);
    markHealthy();
    registered.set(true);
    initialized.set(true);
    LOG.info(
        "Worker ready id={} schema={} execution=enabled stage=4", workerId, SchemaVersion.EXPECTED);
  }

  @Scheduled(fixedDelay = 5000, initialDelay = 5000)
  public void heartbeat() {
    if (!initialized.get()) {
      return;
    }
    try {
      schema.verify();
      if (mapper.heartbeat(workerId) != 1) {
        registered.set(false);
        LOG.warn("Worker heartbeat row is unavailable; polling suspended");
      } else {
        markHealthy();
        registered.set(true);
      }
    } catch (DataAccessException | IllegalStateException ex) {
      registered.set(false);
      LOG.warn(
          "Worker heartbeat/schema check failed type={} polling=suspended",
          ex.getClass().getSimpleName());
    }
  }

  public boolean ready() {
    return registered.get();
  }

  private void markHealthy() {
    String marker = System.getenv("TF_WORKER_HEARTBEAT_FILE");
    if (marker == null || marker.isBlank()) return;
    try {
      Path path = Path.of(marker);
      if (!Files.exists(path)) Files.createFile(path);
      Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
    } catch (IOException ex) {
      throw new IllegalStateException("Worker health marker unavailable", ex);
    }
  }

  public String workerId() {
    return workerId;
  }
}
