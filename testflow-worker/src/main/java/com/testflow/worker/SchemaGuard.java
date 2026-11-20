package com.testflow.worker;

import com.testflow.persistence.SchemaVersion;
import com.testflow.persistence.mapper.WorkerMapper;
import org.springframework.stereotype.Component;

@Component
public class SchemaGuard {
  private final WorkerMapper mapper;

  public SchemaGuard(WorkerMapper mapper) {
    this.mapper = mapper;
  }

  public void verify() {
    String version = mapper.latestVersion();
    if (!SchemaVersion.EXPECTED.equals(version) || mapper.failedMigrations() != 0) {
      throw new IllegalStateException(
          "Worker refuses incompatible or failed database schema; expected "
              + SchemaVersion.EXPECTED);
    }
  }
}
