package com.testflow.persistence.mapper;

import com.testflow.persistence.entity.TestPlanEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface TestPlanMapper {
  @Select("SELECT * FROM tf_test_plan WHERE project_id=#{projectId} AND id=#{id} FOR UPDATE")
  TestPlanEntity lock(@Param("projectId") long projectId, @Param("id") long id);

  @Select("SELECT * FROM tf_test_plan WHERE project_id=#{projectId} AND id=#{id}")
  TestPlanEntity find(@Param("projectId") long projectId, @Param("id") long id);

  @Select(
      """
<script>SELECT id,project_id,name,status,version,created_at,updated_at,created_by,updated_by,execution_mode,fail_fast,max_concurrency FROM tf_test_plan
WHERE project_id=#{projectId}
<if test="name != null"> AND name LIKE CONCAT('%',#{name},'%')</if>
<if test="status != null"> AND status=#{status}</if>
ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>
""")
  List<TestPlanEntity> list(
      @Param("projectId") long projectId,
      @Param("name") String name,
      @Param("status") String status,
      @Param("offset") int offset,
      @Param("limit") int limit);

  @Select(
      """
      <script>SELECT COUNT(*) FROM tf_test_plan WHERE project_id=#{projectId}
      <if test="name != null"> AND name LIKE CONCAT('%',#{name},'%')</if>
      <if test="status != null"> AND status=#{status}</if></script>
      """)
  long count(
      @Param("projectId") long projectId,
      @Param("name") String name,
      @Param("status") String status);

  @Insert(
      """
INSERT INTO tf_test_plan(project_id,name,execution_mode,fail_fast,max_concurrency,status,created_by,updated_by)
VALUES(#{projectId},#{name},#{executionMode},#{failFast},#{maxConcurrency},#{status},#{createdBy},#{updatedBy})
""")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(TestPlanEntity entity);

  @Update(
      """
UPDATE tf_test_plan SET name=#{name},execution_mode=#{executionMode},fail_fast=#{failFast},max_concurrency=#{maxConcurrency},status=#{status},version=version+1,
updated_by=#{updatedBy},updated_at=UTC_TIMESTAMP(3) WHERE project_id=#{projectId} AND id=#{id} AND version=#{version}
""")
  int update(TestPlanEntity entity);
}
