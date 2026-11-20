package com.testflow.worker;

import static org.assertj.core.api.Assertions.*;

import com.testflow.persistence.mapper.WorkerMapper;
import org.junit.jupiter.api.Test;

class SchemaGuardTest {
  @Test
  void acceptsOnlyExactSuccessfulSchema() {
    assertThatCode(
            () ->
                new SchemaGuard(mapper(com.testflow.persistence.SchemaVersion.EXPECTED, 0))
                    .verify())
        .doesNotThrowAnyException();
  }

  @Test
  void refusesMissingOrDifferentSchema() {
    for (String version : new String[] {null, "0", "1", "999"}) {
      assertThatThrownBy(() -> new SchemaGuard(mapper(version, 0)).verify())
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void refusesFailedMigrationWithoutWritingToDatabase() {
    assertThatThrownBy(
            () ->
                new SchemaGuard(mapper(com.testflow.persistence.SchemaVersion.EXPECTED, 1))
                    .verify())
        .isInstanceOf(IllegalStateException.class);
  }

  private WorkerMapper mapper(String version, int failed) {
    return new WorkerMapper() {
      @Override
      public String latestVersion() {
        return version;
      }

      @Override
      public int failedMigrations() {
        return failed;
      }

      @Override
      public int register(String id, String app, String schema) {
        throw new AssertionError("Schema check must not write");
      }

      @Override
      public int heartbeat(String id) {
        throw new AssertionError("Schema check must not write");
      }
    };
  }
}
