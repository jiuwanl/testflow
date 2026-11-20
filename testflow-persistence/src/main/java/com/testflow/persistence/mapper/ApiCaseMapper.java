package com.testflow.persistence.mapper;

import com.testflow.persistence.entity.ApiCaseEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface ApiCaseMapper {
  @Select("SELECT * FROM tf_api_case WHERE project_id=#{projectId} AND id=#{id}")
  ApiCaseEntity find(@Param("projectId") long projectId, @Param("id") long id);

  @Select("SELECT * FROM tf_api_case WHERE project_id=#{projectId} AND id=#{id} FOR UPDATE")
  ApiCaseEntity lock(@Param("projectId") long projectId, @Param("id") long id);

  @Select(
      """
<script>SELECT id,project_id,name,status,version,created_at,updated_at,created_by,updated_by,priority,tags_json,config_schema_version FROM tf_api_case
WHERE project_id=#{projectId}
<if test="name != null"> AND name LIKE CONCAT('%',#{name},'%')</if>
<if test="status != null"> AND status=#{status}</if>
ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>
""")
  List<ApiCaseEntity> list(
      @Param("projectId") long projectId,
      @Param("name") String name,
      @Param("status") String status,
      @Param("offset") int offset,
      @Param("limit") int limit);

  @Select(
      """
      <script>SELECT COUNT(*) FROM tf_api_case WHERE project_id=#{projectId}
      <if test="name != null"> AND name LIKE CONCAT('%',#{name},'%')</if>
      <if test="status != null"> AND status=#{status}</if></script>
      """)
  long count(
      @Param("projectId") long projectId,
      @Param("name") String name,
      @Param("status") String status);

  @Insert(
      """
INSERT INTO tf_api_case(project_id,name,tags_json,priority,config_schema_version,config_json,status,created_by,updated_by)
VALUES(#{projectId},#{name},#{tagsJson},#{priority},#{configSchemaVersion},#{configJson},#{status},#{createdBy},#{updatedBy})
""")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(ApiCaseEntity entity);

  @Update(
      """
UPDATE tf_api_case SET name=#{name},tags_json=#{tagsJson},priority=#{priority},config_schema_version=#{configSchemaVersion},config_json=#{configJson},status=#{status},version=version+1,
updated_by=#{updatedBy},updated_at=UTC_TIMESTAMP(3) WHERE project_id=#{projectId} AND id=#{id} AND version=#{version}
""")
  int update(ApiCaseEntity entity);
}
