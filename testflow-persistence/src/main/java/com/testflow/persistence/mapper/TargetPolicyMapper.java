package com.testflow.persistence.mapper;

import com.testflow.persistence.entity.TargetPolicyEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface TargetPolicyMapper {
  @Select(
      "SELECT * FROM tf_target_policy WHERE project_id=#{projectId} ORDER BY id DESC LIMIT #{limit}"
          + " OFFSET #{offset}")
  List<TargetPolicyEntity> list(
      @Param("projectId") long projectId, @Param("offset") int offset, @Param("limit") int limit);

  @Select("SELECT COUNT(*) FROM tf_target_policy WHERE project_id=#{projectId}")
  long count(long projectId);

  @Select("SELECT * FROM tf_target_policy WHERE project_id=#{projectId} AND id=#{id}")
  TargetPolicyEntity find(@Param("projectId") long projectId, @Param("id") long id);

  @Select(
      "SELECT * FROM tf_target_policy WHERE project_id=#{projectId} AND scheme=#{scheme} AND"
          + " host=#{host} AND port=#{port} AND enabled=TRUE FOR SHARE")
  TargetPolicyEntity approved(
      @Param("projectId") long projectId,
      @Param("scheme") String scheme,
      @Param("host") String host,
      @Param("port") int port);

  @Insert(
      """
INSERT INTO tf_target_policy(project_id,scheme,host,port,address_scope,enabled,created_by,updated_by)
VALUES(#{projectId},#{scheme},#{host},#{port},#{addressScope},#{enabled},#{createdBy},#{updatedBy})
""")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(TargetPolicyEntity entity);

  @Update(
      """
UPDATE tf_target_policy SET enabled=#{enabled},version=version+1,updated_by=#{updatedBy},updated_at=UTC_TIMESTAMP(3)
WHERE project_id=#{projectId} AND id=#{id} AND version=#{version}
""")
  int update(TargetPolicyEntity entity);
}
