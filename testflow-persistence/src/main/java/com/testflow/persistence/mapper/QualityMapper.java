package com.testflow.persistence.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface QualityMapper {
  record Identity(long runId, String eventId, String payloadHash) {}

  @Select("SELECT run_id,event_id,payload_hash FROM tf_quality_run_fact WHERE run_id=#{id}")
  Identity fact(long id);

  @Insert(
      """
INSERT INTO tf_quality_run_fact(run_id,event_id,project_id,kind,source_id,env_id,finished_at,state,outcome,total_count,passed_count,failed_count,error_count,skipped_count,cancelled_count,unknown_count,duration_ms,payload_hash)
SELECT r.id,e.event_id,r.project_id,r.kind,r.source_id,r.env_id,r.finished_at,r.state,r.outcome,r.total_count,r.passed_count,r.failed_count,r.error_count,r.skipped_count,r.cancelled_count,r.unknown_count,
 CASE WHEN r.started_at IS NULL THEN NULL ELSE TIMESTAMPDIFF(MICROSECOND,r.started_at,r.finished_at) DIV 1000 END,#{hash}
FROM tf_outbox_event e JOIN tf_test_run r ON r.id=e.run_id WHERE e.event_id=#{eventId}
ON DUPLICATE KEY UPDATE run_id=tf_quality_run_fact.run_id
""")
  int insert(@Param("eventId") String eventId, @Param("hash") String hash);

  @Update(
      "UPDATE tf_quality_failure SET status='RESOLVED',last_seen_at=UTC_TIMESTAMP(3) WHERE"
          + " event_id=#{id}")
  int resolved(String id);

  @Insert(
      """
INSERT INTO tf_quality_failure(topic,partition_id,record_offset,event_id,project_id,payload_hash,error_code)
VALUES(#{topic},#{partition},#{offset},#{eventId},#{projectId},#{hash},#{code})
ON DUPLICATE KEY UPDATE last_seen_at=UTC_TIMESTAMP(3),error_code=#{code},status='OPEN'
""")
  int park(
      @Param("topic") String topic,
      @Param("partition") int partition,
      @Param("offset") long offset,
      @Param("eventId") String eventId,
      @Param("projectId") Long projectId,
      @Param("hash") String hash,
      @Param("code") String code);

  @Select(
      "SELECT id,project_id,event_id,error_code,status,replay_count,first_seen_at,last_seen_at FROM"
          + " tf_quality_failure WHERE project_id=#{projectId} ORDER BY id DESC LIMIT #{limit}"
          + " OFFSET #{offset}")
  List<Map<String, Object>> failures(
      @Param("projectId") long projectId, @Param("offset") int offset, @Param("limit") int limit);

  @Select("SELECT COUNT(*) FROM tf_quality_failure WHERE project_id=#{id}")
  long failureCount(long id);

  @Select(
      "SELECT id,event_id,project_id,status FROM tf_quality_failure WHERE id=#{id} AND"
          + " project_id=#{projectId}")
  Map<String, Object> failure(@Param("projectId") long projectId, @Param("id") long id);

  @Update(
      "UPDATE tf_quality_failure SET"
          + " status='REPLAY_REQUESTED',replay_count=replay_count+1,last_seen_at=UTC_TIMESTAMP(3)"
          + " WHERE id=#{id}")
  int replayed(long id);

  @Select(
      "SELECT id,topic,partition_id,record_offset,event_id,error_code,status,first_seen_at FROM"
          + " tf_quality_failure WHERE project_id IS NULL ORDER BY id DESC LIMIT 100")
  List<Map<String, Object>> unassignedFailures();

  record Filter(
      long projectId,
      String kind,
      Long sourceId,
      Long envId,
      java.time.Instant from,
      java.time.Instant to,
      boolean includeIncomplete) {}

  String WHERE =
      """
 WHERE r.project_id=#{f.projectId} AND r.state IN ('COMPLETED','CANCELLED','TIMED_OUT','INTERRUPTED')
 AND r.finished_at &gt;= #{f.from} AND r.finished_at &lt; #{f.to}
 <if test='f.kind!=null'>AND r.kind=#{f.kind}</if><if test='f.sourceId!=null'>AND r.source_id=#{f.sourceId}</if><if test='f.envId!=null'>AND r.env_id=#{f.envId}</if>
""";

  @Select(
      "<script>SELECT COUNT(*) AS terminalRuns,COUNT(q.run_id) AS projectedRuns,MAX(q.projected_at)"
          + " AS latestProjectedAt,MIN(CASE WHEN q.run_id IS NULL THEN r.finished_at END) AS"
          + " oldestMissingAt,SUM(CASE WHEN q.run_id IS NULL AND e.status IN"
          + " ('PENDING','PUBLISHING') THEN 1 ELSE 0 END) AS pendingEvents,SUM(CASE WHEN"
          + " r.state&lt;>'COMPLETED' THEN 1 ELSE 0 END) AS incompleteRuns FROM tf_test_run r LEFT"
          + " JOIN tf_quality_run_fact q ON q.run_id=r.id LEFT JOIN tf_outbox_event e ON"
          + " e.run_id=r.id "
          + WHERE
          + "</script>")
  Map<String, Object> status(@Param("f") Filter filter);

  String SUMS =
      "COUNT(*) AS runs,SUM(q.total_count) AS total,SUM(q.passed_count) AS"
          + " passed,SUM(q.failed_count) AS failed,SUM(q.error_count) AS"
          + " errors,SUM(q.skipped_count) AS skipped,SUM(q.cancelled_count) AS"
          + " cancelled,SUM(q.unknown_count) AS `unknown`,AVG(q.duration_ms) AS averageDurationMs";
  String FACTS =
      " FROM tf_quality_run_fact q JOIN tf_test_run r ON r.id=q.run_id "
          + WHERE
          + "<if test='!f.includeIncomplete'>AND q.state='COMPLETED'</if>";

  @Select("<script>SELECT " + SUMS + FACTS + "</script>")
  Map<String, Object> totals(@Param("f") Filter filter);

  @Select(
      "<script>SELECT DATE_FORMAT(q.finished_at,'%Y-%m-%d') AS day,"
          + SUMS
          + FACTS
          + " GROUP BY day ORDER BY day</script>")
  List<Map<String, Object>> days(@Param("f") Filter filter);

  @Select(
      "<script>SELECT CAST(q.run_id AS CHAR) AS runId,r.source_name_snapshot AS"
          + " sourceName,q.state,q.outcome,q.finished_at AS finishedAt"
          + FACTS
          + " ORDER BY q.finished_at DESC,q.run_id DESC LIMIT 20</script>")
  List<Map<String, Object>> recent(@Param("f") Filter filter);
}
