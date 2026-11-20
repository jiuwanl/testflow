package com.testflow.persistence.mapper;

import org.apache.ibatis.annotations.*;

public interface WorkerMapper {
  @Select(
      "SELECT version FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL ORDER"
          + " BY installed_rank DESC LIMIT 1")
  String latestVersion();

  @Select("SELECT COUNT(*) FROM flyway_schema_history WHERE success=FALSE")
  int failedMigrations();

  @Insert(
      """
INSERT INTO tf_worker_node(worker_id,application_version,expected_schema_version,last_seen_at,started_at)
VALUES(#{workerId},#{applicationVersion},#{schemaVersion},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
""")
  int register(
      @Param("workerId") String workerId,
      @Param("applicationVersion") String applicationVersion,
      @Param("schemaVersion") String schemaVersion);

  @Update("UPDATE tf_worker_node SET last_seen_at=UTC_TIMESTAMP(3) WHERE worker_id=#{workerId}")
  int heartbeat(String workerId);
}
