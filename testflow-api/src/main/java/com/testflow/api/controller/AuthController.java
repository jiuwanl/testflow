package com.testflow.api.controller;

import com.testflow.api.dto.UserDtos;
import com.testflow.api.security.*;
import com.testflow.api.service.AccountService;
import com.testflow.api.web.ApiResponse;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AccountService accounts;
  private final CurrentAccount current;
  private final LoginRateLimiter limits;
  private final SessionAuthenticationStrategy sessions;
  private final HttpSessionSecurityContextRepository contexts;
  private final HttpSessionCsrfTokenRepository csrf;

  public AuthController(
      AccountService accounts,
      CurrentAccount current,
      LoginRateLimiter limits,
      SessionAuthenticationStrategy sessions,
      HttpSessionSecurityContextRepository contexts,
      HttpSessionCsrfTokenRepository csrf) {
    this.accounts = accounts;
    this.current = current;
    this.limits = limits;
    this.sessions = sessions;
    this.contexts = contexts;
    this.csrf = csrf;
  }

  @GetMapping("/csrf")
  public ApiResponse<?> csrf(CsrfToken token) {
    return ApiResponse.of(Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
  }

  @PostMapping(value = "/login", consumes = "application/json")
  public ApiResponse<?> login(
      @Valid @RequestBody UserDtos.Login input,
      HttpServletRequest request,
      HttpServletResponse response) {
    limits.check(
        input.username().trim().toLowerCase(java.util.Locale.ROOT), request.getRemoteAddr());
    var user = accounts.authenticate(input.username(), input.password());
    var auth =
        UsernamePasswordAuthenticationToken.authenticated(
            new AccountPrincipal(user.getId(), user.getVersion()),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));
    sessions.onAuthentication(auth, request, response);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(auth);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    return ApiResponse.of(UserDtos.View.from(user));
  }

  @GetMapping("/me")
  public ApiResponse<?> me() {
    return ApiResponse.of(UserDtos.View.from(current.get()));
  }

  @PostMapping(value = "/logout", consumes = "application/json")
  public ApiResponse<?> logout(HttpServletRequest request, HttpServletResponse response) {
    new CsrfLogoutHandler(csrf)
        .logout(request, response, SecurityContextHolder.getContext().getAuthentication());
    new SecurityContextLogoutHandler()
        .logout(request, response, SecurityContextHolder.getContext().getAuthentication());
    return ApiResponse.of(Map.of("loggedOut", true));
  }
}
