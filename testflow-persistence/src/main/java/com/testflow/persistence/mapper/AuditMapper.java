package com.testflow.persistence.mapper;

import org.apache.ibatis.annotations.*;

public interface AuditMapper {
  @Insert(
      """
INSERT INTO tf_audit_event(project_id,actor_id,action,resource_type,resource_id,summary_json,run_id)
VALUES(#{projectId},#{actorId},#{action},#{resourceType},#{resourceId},'{}',CASE WHEN #{resourceType}='RUN' THEN #{resourceId} ELSE NULL END)
""")
  int insert(
      @Param("projectId") Long projectId,
      @Param("actorId") long actorId,
      @Param("action") String action,
      @Param("resourceType") String resourceType,
      @Param("resourceId") Long resourceId);

  @Insert(
      "INSERT INTO"
          + " tf_audit_event(project_id,actor_id,action,resource_type,resource_id,summary_json)"
          + " VALUES(#{projectId},#{actorId},#{action},#{resourceType},#{resourceId},#{summary})")
  int insertDetail(
      @Param("projectId") Long projectId,
      @Param("actorId") long actorId,
      @Param("action") String action,
      @Param("resourceType") String resourceType,
      @Param("resourceId") Long resourceId,
      @Param("summary") String summary);
}
