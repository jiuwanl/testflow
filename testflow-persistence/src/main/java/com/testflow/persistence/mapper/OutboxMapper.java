package com.testflow.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

public interface OutboxMapper {
  record Event(
      String eventId,
      long runId,
      long projectId,
      String payload,
      String status,
      int attemptCount,
      String claimToken) {}

  String COLUMNS =
      "event_id,run_id,project_id,CAST(payload AS CHAR) AS"
          + " payload,status,attempt_count,claim_token";

  @Select("SELECT " + COLUMNS + " FROM tf_outbox_event WHERE event_id=#{id}")
  Event find(String id);

  @Select("SELECT " + COLUMNS + " FROM tf_outbox_event WHERE run_id=#{id}")
  Event byRun(long id);

  @Select("SELECT " + COLUMNS + " FROM tf_outbox_event WHERE event_id=#{id} FOR UPDATE")
  Event lock(String id);

  @Select(
      "SELECT "
          + COLUMNS
          + " FROM tf_outbox_event WHERE status='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(3)"
          + " ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED")
  Event next();

  @Select(
      "SELECT "
          + COLUMNS
          + " FROM tf_outbox_event WHERE status='PUBLISHING' AND lease_until<=UTC_TIMESTAMP(3)"
          + " ORDER BY lease_until,event_id LIMIT 1 FOR UPDATE SKIP LOCKED")
  Event expired();

  @Update(
      "UPDATE tf_outbox_event SET"
          + " status='PUBLISHING',claim_token=#{token},lease_until=TIMESTAMPADD(SECOND,15,UTC_TIMESTAMP(3)),attempt_count=attempt_count+1"
          + " WHERE event_id=#{id}")
  int claim(@Param("id") String id, @Param("token") String token);

  @Update(
      "UPDATE tf_outbox_event SET"
          + " status='PUBLISHED',published_at=UTC_TIMESTAMP(3),lease_until=NULL,claim_token=NULL,last_error_code=NULL"
          + " WHERE event_id=#{id} AND status='PUBLISHING' AND claim_token=#{token} AND"
          + " lease_until>UTC_TIMESTAMP(3)")
  int published(@Param("id") String id, @Param("token") String token);

  @Update(
      "UPDATE tf_outbox_event SET"
          + " status='PENDING',next_attempt_at=TIMESTAMPADD(SECOND,LEAST(60,POW(2,LEAST(attempt_count,6))),UTC_TIMESTAMP(3)),lease_until=NULL,claim_token=NULL,last_error_code='BROKER_SEND_FAILED'"
          + " WHERE event_id=#{id} AND status='PUBLISHING' AND claim_token=#{token} AND"
          + " lease_until>UTC_TIMESTAMP(3)")
  int failed(@Param("id") String id, @Param("token") String token);

  @Update(
      "UPDATE tf_outbox_event SET"
          + " status='PENDING',next_attempt_at=UTC_TIMESTAMP(3),claim_token=NULL,lease_until=NULL,last_error_code=NULL"
          + " WHERE event_id=#{id} AND (status<>'PUBLISHING' OR lease_until<=UTC_TIMESTAMP(3))")
  int requeue(String id);

  @Select(
      "<script>SELECT r.id FROM tf_test_run r LEFT JOIN tf_outbox_event e ON e.run_id=r.id WHERE"
          + " r.state IN ('COMPLETED','CANCELLED','TIMED_OUT','INTERRUPTED') AND e.run_id IS NULL"
          + " <if test='projectId!=null'>AND r.project_id=#{projectId}</if> ORDER BY r.id LIMIT"
          + " #{limit}</script>")
  List<Long> missingEvents(@Param("projectId") Long projectId, @Param("limit") int limit);

  @Select(
      "SELECT e.event_id FROM tf_outbox_event e LEFT JOIN tf_quality_run_fact f ON"
          + " f.run_id=e.run_id WHERE e.project_id=#{projectId} AND f.run_id IS NULL AND"
          + " e.status='PUBLISHED' ORDER BY e.run_id LIMIT #{limit}")
  List<String> missingFacts(@Param("projectId") long projectId, @Param("limit") int limit);
}
