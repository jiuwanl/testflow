package com.testflow.persistence.mapper;

import com.testflow.core.execution.RunLease;
import com.testflow.persistence.entity.RunEntity;
import com.testflow.persistence.entity.RunItemEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface RunMapper {
  String COLUMNS =
      "r.*, (r.lease_until>UTC_TIMESTAMP(3)) AS lease_valid, "
          + "(r.deadline_at<=UTC_TIMESTAMP(3)) AS deadline_expired";

  @Insert(
      """
INSERT INTO tf_outbox_event(event_id,run_id,project_id,payload)
SELECT #{eventId},id,project_id,JSON_OBJECT(
 'schemaVersion',1,'eventId',#{eventId},'runId',CAST(id AS CHAR),'projectId',CAST(project_id AS CHAR),
 'kind',kind,'sourceId',CAST(source_id AS CHAR),'environmentId',CAST(env_id AS CHAR),
 'finishedAt',DATE_FORMAT(finished_at,'%Y-%m-%dT%H:%i:%s.%fZ'),'state',state,'outcome',outcome,
 'total',total_count,'passed',passed_count,'failed',failed_count,'errors',error_count,
 'skipped',skipped_count,'cancelled',cancelled_count,'unknown',unknown_count,
 'durationMs',CASE WHEN started_at IS NULL THEN NULL ELSE TIMESTAMPDIFF(MICROSECOND,started_at,finished_at) DIV 1000 END)
FROM tf_test_run WHERE id=#{id} AND state IN ('COMPLETED','CANCELLED','TIMED_OUT','INTERRUPTED')
""")
  int enqueueFinalized(@Param("id") long id, @Param("eventId") String eventId);

  @Select("SELECT " + COLUMNS + " FROM tf_test_run r WHERE r.id=#{id}")
  RunEntity find(long id);

  @Select("SELECT " + COLUMNS + " FROM tf_test_run r WHERE r.id=#{id} FOR UPDATE")
  RunEntity lock(long id);

  @Select(
      "SELECT "
          + COLUMNS
          + " FROM tf_test_run r WHERE project_id=#{projectId} AND requested_by=#{userId} AND"
          + " idempotency_key=#{key}")
  RunEntity byKey(
      @Param("projectId") long projectId, @Param("userId") long userId, @Param("key") String key);

  String XML_COLUMNS =
      "r.*, (r.lease_until>UTC_TIMESTAMP(3)) AS lease_valid, (r.deadline_at &lt;=UTC_TIMESTAMP(3))"
          + " AS deadline_expired";
  String FILTER =
      """
 WHERE project_id=#{projectId}
 <if test="f.sourceType != null">AND kind=#{f.sourceType}</if>
 <if test="f.sourceId != null">AND source_id=#{f.sourceId}</if>
 <if test="f.environmentId != null">AND env_id=#{f.environmentId}</if>
 <if test="f.state != null">AND state=#{f.state}</if>
 <if test="f.outcome != null">AND outcome=#{f.outcome}</if>
 <if test="f.createdFrom != null">AND created_at &gt;= #{f.createdFrom}</if>
 <if test="f.createdTo != null">AND created_at &lt; #{f.createdTo}</if>
""";

  @Select(
      "<script>SELECT "
          + XML_COLUMNS
          + " FROM tf_test_run r JOIN (SELECT id FROM tf_test_run "
          + FILTER
          + " ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}) page ON"
          + " page.id=r.id ORDER BY r.created_at DESC,r.id DESC</script>")
  List<RunEntity> list(
      @Param("projectId") long projectId,
      @Param("f") com.testflow.persistence.RunFilter filter,
      @Param("offset") int offset,
      @Param("limit") int limit);

  @Select("<script>SELECT COUNT(*) FROM tf_test_run " + FILTER + "</script>")
  long count(
      @Param("projectId") long projectId, @Param("f") com.testflow.persistence.RunFilter filter);

  @Insert(
      """
INSERT INTO tf_test_run(project_id,requested_by,idempotency_key,request_hash,kind,source_id,env_id,
  source_name_snapshot,environment_name_snapshot,requested_by_name,state,created_at,queued_at,deadline_at,total_count,parent_run_id,execution_mode,max_concurrency)
VALUES(#{run.projectId},#{run.requestedBy},#{run.idempotencyKey},#{run.requestHash},#{run.kind},#{run.sourceId},#{run.envId},
  #{run.sourceNameSnapshot},#{run.environmentNameSnapshot},#{run.requestedByName},'QUEUED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),
  TIMESTAMPADD(MICROSECOND,#{timeoutMs}*1000,UTC_TIMESTAMP(3)),#{run.totalCount},#{run.parentRunId},#{run.executionMode},#{run.maxConcurrency})
""")
  @Options(useGeneratedKeys = true, keyProperty = "run.id")
  int insert(@Param("run") RunEntity run, @Param("timeoutMs") long timeoutMs);

  @Insert(
      """
INSERT INTO tf_run_snapshot(run_id,project_id,schema_version,content_hmac,encrypted_payload,key_version)
VALUES(#{runId},#{projectId},3,#{hmac},#{payload},'v1')
""")
  int snapshot(
      @Param("runId") long runId,
      @Param("projectId") long projectId,
      @Param("hmac") String hmac,
      @Param("payload") String payload);

  record StoredSnapshot(String encryptedPayload, String contentHmac) {}

  @Select("SELECT encrypted_payload,content_hmac FROM tf_run_snapshot WHERE run_id=#{id}")
  StoredSnapshot snapshotFor(long id);

  @Insert(
      """
      INSERT INTO tf_run_item(project_id,run_id,ordinal,source_case_id,case_name_snapshot,state)
      VALUES(#{projectId},#{runId},#{ordinal},#{sourceCaseId},#{caseNameSnapshot},'PENDING')
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertItem(RunItemEntity item);

  @Select("SELECT * FROM tf_run_item WHERE run_id=#{id} ORDER BY ordinal")
  List<RunItemEntity> items(long id);

  @Select(
      """
SELECT i.*,r.report_json,r.body_available,r.body_truncated
FROM tf_run_item i LEFT JOIN tf_run_item_result r ON r.item_id=i.id AND r.project_id=i.project_id
WHERE i.run_id=#{runId} AND i.id=#{itemId}
""")
  RunItemEntity itemDetail(@Param("runId") long runId, @Param("itemId") long itemId);

  @Select(
      "SELECT "
          + COLUMNS
          + " FROM tf_test_run r WHERE state='QUEUED' AND kind IN ('CASE','PLAN') AND"
          + " deadline_at>UTC_TIMESTAMP(3) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED")
  RunEntity nextQueued();

  @Update(
      """
UPDATE tf_test_run SET state='RUNNING',owner_id=#{owner},lease_version=lease_version+1,
  lease_until=TIMESTAMPADD(MICROSECOND,#{leaseMs}*1000,UTC_TIMESTAMP(3)),heartbeat_at=UTC_TIMESTAMP(3),started_at=UTC_TIMESTAMP(3)
WHERE id=#{id} AND state='QUEUED' AND deadline_at>UTC_TIMESTAMP(3)
""")
  int claim(@Param("id") long id, @Param("owner") String owner, @Param("leaseMs") long leaseMs);

  @Update(
      """
UPDATE tf_test_run SET heartbeat_at=UTC_TIMESTAMP(3),lease_until=TIMESTAMPADD(MICROSECOND,#{leaseMs}*1000,UTC_TIMESTAMP(3))
WHERE id=#{runId} AND owner_id=#{owner} AND lease_version=#{version} AND lease_until>UTC_TIMESTAMP(3)
  AND deadline_at>UTC_TIMESTAMP(3) AND state IN ('RUNNING','CANCELLING')
""")
  int renew(
      @Param("runId") long id,
      @Param("owner") String owner,
      @Param("version") long version,
      @Param("leaseMs") long leaseMs);

  @Update(
      """
      UPDATE tf_run_item i JOIN tf_test_run r ON r.id=i.run_id
      SET i.state='RUNNING',i.started_at=UTC_TIMESTAMP(3)
      WHERE i.id=#{id} AND i.state='PENDING' AND r.id=#{lease.runId} AND r.owner_id=#{lease.ownerId}
        AND r.lease_version=#{lease.version} AND r.lease_until>UTC_TIMESTAMP(3)
        AND r.deadline_at>UTC_TIMESTAMP(3) AND r.state='RUNNING'
      """)
  int beginItem(@Param("id") long id, @Param("lease") RunLease lease);

  @Update(
      """
UPDATE tf_run_item i JOIN tf_test_run r ON r.id=i.run_id
SET i.state=#{state},i.error_code=#{code},i.duration_ms=#{duration},i.finished_at=UTC_TIMESTAMP(3)
WHERE i.id=#{id} AND i.state='RUNNING' AND r.id=#{lease.runId} AND r.owner_id=#{lease.ownerId}
  AND r.lease_version=#{lease.version} AND r.lease_until>UTC_TIMESTAMP(3)
  AND r.deadline_at>UTC_TIMESTAMP(3) AND r.state IN ('RUNNING','CANCELLING')
""")
  int finishItem(
      @Param("id") long id,
      @Param("state") String state,
      @Param("code") String code,
      @Param("duration") long duration,
      @Param("lease") RunLease lease);

  @Insert(
      """
INSERT INTO tf_run_item_result(item_id,project_id,report_json,body_available,body_truncated)
SELECT #{itemId},#{projectId},#{report},#{available},#{truncated} FROM tf_test_run r
WHERE r.id=#{lease.runId} AND r.owner_id=#{lease.ownerId} AND r.lease_version=#{lease.version}
  AND r.lease_until>UTC_TIMESTAMP(3) AND r.deadline_at>UTC_TIMESTAMP(3) AND r.state IN ('RUNNING','CANCELLING')
""")
  int result(
      @Param("itemId") long id,
      @Param("projectId") long projectId,
      @Param("report") String report,
      @Param("available") boolean available,
      @Param("truncated") boolean truncated,
      @Param("lease") RunLease lease);

  @Update(
      """
UPDATE tf_run_item SET state=CASE WHEN state='RUNNING' THEN 'UNKNOWN' ELSE #{pendingState} END,
  error_code=#{reason},finished_at=UTC_TIMESTAMP(3)
WHERE run_id=#{id} AND state IN ('RUNNING','PENDING')
""")
  int terminateItems(
      @Param("id") long id,
      @Param("pendingState") String pendingState,
      @Param("reason") String reason);

  @Update(
      """
UPDATE tf_run_item SET state=#{state},error_code=#{reason},finished_at=UTC_TIMESTAMP(3)
WHERE run_id=#{id} AND state='PENDING'
""")
  int stopPending(
      @Param("id") long id, @Param("state") String state, @Param("reason") String reason);

  @Update(
      """
<script>UPDATE tf_test_run SET state=#{run.state},outcome=#{run.outcome},terminal_reason=#{run.terminalReason},
  lease_version=lease_version+1,lease_until=NULL,finished_at=UTC_TIMESTAMP(3),
  passed_count=#{run.passedCount},failed_count=#{run.failedCount},error_count=#{run.errorCount},skipped_count=#{run.skippedCount},
  cancelled_count=#{run.cancelledCount},unknown_count=#{run.unknownCount}
WHERE id=#{run.id} AND state IN ('QUEUED','RUNNING','CANCELLING')
<if test="lease != null">
 AND owner_id=#{lease.ownerId} AND lease_version=#{lease.version} AND lease_until>UTC_TIMESTAMP(3)
 <if test="run.state != 'TIMED_OUT'">AND deadline_at>UTC_TIMESTAMP(3)</if>
</if></script>
""")
  int finalizeRun(@Param("run") RunEntity run, @Param("lease") RunLease lease);

  @Update(
      """
UPDATE tf_test_run SET terminal_reason=#{run.terminalReason},passed_count=#{run.passedCount},failed_count=#{run.failedCount},error_count=#{run.errorCount},
 skipped_count=#{run.skippedCount},cancelled_count=#{run.cancelledCount},unknown_count=#{run.unknownCount}
WHERE id=#{run.id} AND owner_id=#{lease.ownerId} AND lease_version=#{lease.version}
 AND lease_until>UTC_TIMESTAMP(3) AND deadline_at>UTC_TIMESTAMP(3) AND state IN ('RUNNING','CANCELLING')
""")
  int progress(@Param("run") RunEntity run, @Param("lease") RunLease lease);

  @Update("UPDATE tf_test_run SET state='CANCELLING' WHERE id=#{id} AND state='RUNNING'")
  int requestCancel(long id);

  @Select(
      "SELECT "
          + COLUMNS
          + " FROM tf_test_run r WHERE state IN ('RUNNING','CANCELLING') AND"
          + " lease_until<=UTC_TIMESTAMP(3) ORDER BY lease_until,id LIMIT 1 FOR UPDATE SKIP LOCKED")
  RunEntity expiredLease();

  @Select(
      "SELECT "
          + COLUMNS
          + " FROM tf_test_run r WHERE state IN ('QUEUED','RUNNING','CANCELLING') AND"
          + " deadline_at<=UTC_TIMESTAMP(3) ORDER BY deadline_at,id LIMIT 1 FOR UPDATE SKIP LOCKED")
  RunEntity expiredDeadline();

  @Select(
      """
      SELECT COUNT(*) FROM tf_project p JOIN tf_project_member m ON m.project_id=p.id
      JOIN tf_user u ON u.id=m.user_id WHERE p.id=#{projectId} AND u.id=#{userId}
        AND p.status='ACTIVE' AND u.status='ACTIVE' AND m.role IN ('OWNER','MAINTAINER')
      """)
  int authorized(@Param("projectId") long projectId, @Param("userId") long userId);

  @Select(
      """
SELECT COUNT(*) FROM tf_test_run WHERE id=#{runId} AND owner_id=#{owner} AND lease_version=#{version}
  AND lease_until>UTC_TIMESTAMP(3) AND deadline_at>UTC_TIMESTAMP(3) AND state='RUNNING'
""")
  int dispatchAllowed(
      @Param("runId") long id, @Param("owner") String owner, @Param("version") long version);
}
