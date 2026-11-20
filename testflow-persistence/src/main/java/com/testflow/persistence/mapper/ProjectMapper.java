package com.testflow.persistence.mapper;

import com.testflow.persistence.entity.MemberEntity;
import com.testflow.persistence.entity.ProjectEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface ProjectMapper {
  @Select("SELECT * FROM tf_project WHERE id=#{id}")
  ProjectEntity findById(long id);

  @Select("SELECT * FROM tf_project WHERE id=#{id} FOR UPDATE")
  ProjectEntity lockById(long id);

  @Select(
      """
SELECT p.*,m.role AS current_role FROM tf_project p JOIN tf_project_member m ON m.project_id=p.id
WHERE m.user_id=#{userId} ORDER BY p.id DESC LIMIT #{limit} OFFSET #{offset}
""")
  List<ProjectEntity> list(
      @Param("userId") long userId, @Param("offset") int offset, @Param("limit") int limit);

  @Select("SELECT COUNT(*) FROM tf_project_member WHERE user_id=#{userId}")
  long count(long userId);

  @Insert(
      "INSERT INTO tf_project(name,status,created_by,updated_by)"
          + " VALUES(#{name},#{status},#{createdBy},#{updatedBy})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(ProjectEntity project);

  @Update(
      """
      UPDATE tf_project SET name=#{name},status=#{status},version=version+1,updated_by=#{updatedBy},
      updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}
      """)
  int update(ProjectEntity project);

  @Select("SELECT * FROM tf_project_member WHERE project_id=#{projectId} AND user_id=#{userId}")
  MemberEntity member(@Param("projectId") long projectId, @Param("userId") long userId);

  @Select(
      """
SELECT m.*,u.username,u.status AS user_status FROM tf_project_member m JOIN tf_user u ON u.id=m.user_id
WHERE m.project_id=#{projectId} ORDER BY m.id LIMIT #{limit} OFFSET #{offset}
""")
  List<MemberEntity> members(
      @Param("projectId") long projectId, @Param("offset") int offset, @Param("limit") int limit);

  @Select("SELECT COUNT(*) FROM tf_project_member WHERE project_id=#{projectId}")
  long memberCount(long projectId);

  @Select(
      """
      SELECT COUNT(*) FROM tf_project_member m JOIN tf_user u ON u.id=m.user_id
      WHERE m.project_id=#{projectId} AND m.role='OWNER' AND u.status='ACTIVE'
      """)
  int activeOwners(long projectId);

  @Insert(
      """
      INSERT INTO tf_project_member(project_id,user_id,role,created_by,updated_by)
      VALUES(#{projectId},#{userId},#{role},#{createdBy},#{updatedBy})
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insertMember(MemberEntity member);

  @Update(
      """
UPDATE tf_project_member SET role=#{role},version=version+1,updated_by=#{updatedBy},updated_at=UTC_TIMESTAMP(3)
WHERE project_id=#{projectId} AND user_id=#{userId} AND version=#{version}
""")
  int updateMember(MemberEntity member);

  @Delete("DELETE FROM tf_project_member WHERE project_id=#{projectId} AND user_id=#{userId}")
  int removeMember(@Param("projectId") long projectId, @Param("userId") long userId);

  @Select("SELECT * FROM tf_project ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
  List<ProjectEntity> governanceList(@Param("offset") int offset, @Param("limit") int limit);

  @Select("SELECT COUNT(*) FROM tf_project")
  long governanceCount();

  @Select(
      "SELECT p.* FROM tf_project p JOIN tf_project_member m ON m.project_id=p.id WHERE"
          + " m.user_id=#{userId} AND m.role='OWNER' AND p.status='ACTIVE' ORDER BY p.id")
  List<ProjectEntity> activeOwnedProjects(long userId);

  @Select(
      "SELECT m.*,u.username,u.status AS user_status FROM tf_project_member m JOIN tf_user u ON"
          + " u.id=m.user_id WHERE m.project_id=#{projectId} AND m.role='OWNER' ORDER BY m.user_id")
  List<MemberEntity> owners(long projectId);
}
