package com.testflow.api.config;

import com.testflow.core.execution.*;
import com.testflow.persistence.RunStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@Import(RunStore.class)
public class ExecutionConfig {
  @Bean
  SnapshotCodec snapshotCodec(
      JsonMapper json,
      ApiProperties properties,
      @Value("${testflow.execution.snapshot-hmac-key}") String hmac,
      @Value("${testflow.execution.snapshot-max-bytes:4194304}") int maxBytes) {
    return new SnapshotCodec(json, properties.secretKey(), hmac, maxBytes);
  }

  @Bean
  TargetGuard targetGuard(
      @Value("${TF_DB_URL}") String jdbc,
      @Value("${TF_API_PORT:8080}") int apiPort,
      @Value("${TF_REDIS_PORT:6379}") int redisPort,
      @Value("${TF_CONTROL_PORTS:}") String controlPorts) {
    return NetworkBoundary.guard(jdbc, apiPort, redisPort, controlPorts);
  }
}
