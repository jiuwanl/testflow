package com.testflow.persistence.mapper;

import com.testflow.persistence.entity.EnvironmentEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface EnvironmentMapper {
  @Select("SELECT * FROM tf_environment WHERE project_id=#{projectId} AND id=#{id}")
  EnvironmentEntity find(@Param("projectId") long projectId, @Param("id") long id);

  @Select("SELECT * FROM tf_environment WHERE project_id=#{projectId} AND id=#{id} FOR UPDATE")
  EnvironmentEntity lock(@Param("projectId") long projectId, @Param("id") long id);

  @Select(
      """
<script>SELECT id,project_id,name,status,version,created_at,updated_at,created_by,updated_by FROM tf_environment
WHERE project_id=#{projectId}
<if test="name != null"> AND name LIKE CONCAT('%',#{name},'%')</if>
<if test="status != null"> AND status=#{status}</if>
ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>
""")
  List<EnvironmentEntity> list(
      @Param("projectId") long projectId,
      @Param("name") String name,
      @Param("status") String status,
      @Param("offset") int offset,
      @Param("limit") int limit);

  @Select(
      """
      <script>SELECT COUNT(*) FROM tf_environment WHERE project_id=#{projectId}
      <if test="name != null"> AND name LIKE CONCAT('%',#{name},'%')</if>
      <if test="status != null"> AND status=#{status}</if></script>
      """)
  long count(
      @Param("projectId") long projectId,
      @Param("name") String name,
      @Param("status") String status);

  @Insert(
      """
INSERT INTO tf_environment(project_id,name,base_url,variables_json,secret_ciphertext,key_version,status,created_by,updated_by)
VALUES(#{projectId},#{name},#{baseUrl},#{variablesJson},#{secretCiphertext},#{keyVersion},#{status},#{createdBy},#{updatedBy})
""")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(EnvironmentEntity entity);

  @Update(
      """
UPDATE tf_environment SET name=#{name},base_url=#{baseUrl},variables_json=#{variablesJson},secret_ciphertext=#{secretCiphertext},key_version=#{keyVersion},status=#{status},version=version+1,
updated_by=#{updatedBy},updated_at=UTC_TIMESTAMP(3) WHERE project_id=#{projectId} AND id=#{id} AND version=#{version}
""")
  int update(EnvironmentEntity entity);
}
