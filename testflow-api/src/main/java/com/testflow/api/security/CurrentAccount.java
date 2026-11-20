package com.testflow.api.security;

import com.testflow.api.web.ApiException;
import com.testflow.persistence.entity.UserEntity;
import com.testflow.persistence.mapper.UserMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentAccount {
  private final UserMapper users;

  public CurrentAccount(UserMapper users) {
    this.users = users;
  }

  public UserEntity get() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof AccountPrincipal principal)) {
      throw new ApiException(401, "UNAUTHENTICATED", "请先登录");
    }
    UserEntity user = users.findById(principal.id());
    if (user == null
        || !"ACTIVE".equals(user.getStatus())
        || user.getVersion() != principal.version()) {
      throw new ApiException(401, "UNAUTHENTICATED", "登录状态已失效，请重新登录");
    }
    return user;
  }

  public UserEntity admin() {
    UserEntity user = get();
    if (!user.isGlobalAdmin()) {
      throw new ApiException(403, "FORBIDDEN", "需要系统管理员权限");
    }
    return user;
  }
}
