package com.testflow.persistence.mapper;

import com.testflow.persistence.entity.UserEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface UserMapper {
  @Select("SELECT * FROM tf_user WHERE username=#{username}")
  UserEntity findByUsername(String username);

  @Select("SELECT * FROM tf_user WHERE id=#{id}")
  UserEntity findById(long id);

  @Select("SELECT * FROM tf_user WHERE id=#{id} FOR UPDATE")
  UserEntity lockById(long id);

  @Select("SELECT COUNT(*) FROM tf_user")
  long count();

  @Select(
      "SELECT id FROM tf_user WHERE global_admin=TRUE AND status='ACTIVE' ORDER BY id FOR UPDATE")
  List<Long> lockActiveAdmins();

  @Select("SELECT id FROM tf_user WHERE global_admin=TRUE AND status='ACTIVE' ORDER BY id")
  @Options(flushCache = Options.FlushCachePolicy.TRUE)
  List<Long> activeAdminIds();

  @Select("SELECT * FROM tf_user ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
  List<UserEntity> list(@Param("offset") int offset, @Param("limit") int limit);

  @Insert(
      """
      INSERT INTO tf_user(username,password_hash,global_admin,status,created_by,updated_by)
      VALUES(#{username},#{passwordHash},#{globalAdmin},#{status},#{createdBy},#{updatedBy})
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(UserEntity user);

  @Update(
      """
      UPDATE tf_user SET password_hash=#{passwordHash},status=#{status},version=version+1,
      updated_by=#{updatedBy},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND version=#{version}
      """)
  int update(UserEntity user);

  @Select(
      """
      SELECT COUNT(*) FROM tf_project_member m JOIN tf_project p ON p.id=m.project_id
      WHERE m.user_id=#{userId} AND m.role='OWNER' AND p.status='ACTIVE'
      """)
  int countOwnedProjects(long userId);
}
