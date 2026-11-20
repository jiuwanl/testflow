package com.testflow.api;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

class MigrationFailureIT {
  @Test
  void migrationFailurePreventsApplicationStartup() throws Exception {
    String url = System.getenv("TF_DB_URL");
    if (url == null || !url.contains("/testflow_test?"))
      throw new IllegalStateException("Dedicated test database required");
    try {
      assertThatThrownBy(
              () ->
                  SpringApplication.run(
                      ApiApplication.class,
                      "--server.port=0",
                      "--spring.flyway.locations=classpath:db/migration,classpath:db/invalid"))
          .isInstanceOf(RuntimeException.class);
    } finally {
      // Fault-injection cleanup only in testflow_test; production migrations remain immutable.
      try (var connection =
              DriverManager.getConnection(
                  url,
                  System.getenv("TF_MIGRATION_DB_USERNAME"),
                  System.getenv("TF_MIGRATION_DB_PASSWORD"));
          var statement = connection.createStatement()) {
        statement.executeUpdate("DELETE FROM flyway_schema_history WHERE success=0");
      }
    }
  }
}
