package com.testflow.api.config;

import com.testflow.api.web.ApiErrorWriter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfig {
  @Bean
  HttpSessionSecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  HttpSessionCsrfTokenRepository csrfRepository() {
    var repository = new HttpSessionCsrfTokenRepository();
    repository.setHeaderName("X-CSRF-TOKEN");
    return repository;
  }

  @Bean
  SessionAuthenticationStrategy sessionAuthenticationStrategy(HttpSessionCsrfTokenRepository csrf) {
    return new CompositeSessionAuthenticationStrategy(
        List.of(new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ApiErrorWriter errors,
      HttpSessionSecurityContextRepository contexts,
      HttpSessionCsrfTokenRepository csrf)
      throws Exception {
    return http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/login",
                        "/actuator/health",
                        "/actuator/health/readiness",
                        "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(config -> config.csrfTokenRepository(csrf))
        .securityContext(
            config -> config.securityContextRepository(contexts).requireExplicitSave(true))
        .requestCache(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            config ->
                config
                    .authenticationEntryPoint(
                        (req, res, ex) -> errors.write(res, 401, "UNAUTHENTICATED", "请先登录"))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            errors.write(
                                res,
                                403,
                                ex instanceof org.springframework.security.web.csrf.CsrfException
                                    ? "CSRF_INVALID"
                                    : "FORBIDDEN",
                                "请求被拒绝")))
        .build();
  }
}
