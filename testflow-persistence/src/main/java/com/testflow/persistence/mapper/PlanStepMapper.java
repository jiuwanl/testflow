package com.testflow.persistence.mapper;

import com.testflow.persistence.entity.PlanStepEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface PlanStepMapper {
  @Select(
      "SELECT * FROM tf_plan_step WHERE project_id=#{projectId} AND plan_id=#{planId} ORDER BY"
          + " ordinal")
  List<PlanStepEntity> list(@Param("projectId") long projectId, @Param("planId") long planId);

  @Delete("DELETE FROM tf_plan_step WHERE project_id=#{projectId} AND plan_id=#{planId}")
  int removeAll(@Param("projectId") long projectId, @Param("planId") long planId);

  @Insert(
      """
      INSERT INTO tf_plan_step(project_id,plan_id,case_id,ordinal,enabled)
      VALUES(#{projectId},#{planId},#{caseId},#{ordinal},#{enabled})
      """)
  int insert(PlanStepEntity step);
}
