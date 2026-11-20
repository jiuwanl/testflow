package com.testflow.worker;

import com.testflow.core.execution.*;
import com.testflow.persistence.RunStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@Import(RunStore.class)
public class ExecutionWorkerConfig {
  @Bean
  SnapshotCodec snapshotCodec(
      JsonMapper json,
      @Value("${TF_SECRET_KEY}") String key,
      @Value("${TF_SNAPSHOT_HMAC_KEY}") String hmac,
      @Value("${TF_SNAPSHOT_MAX_BYTES:4194304}") int maxBytes) {
    return new SnapshotCodec(json, key, hmac, maxBytes);
  }

  @Bean
  TargetGuard targetGuard(
      @Value("${TF_DB_URL}") String jdbc,
      @Value("${TF_API_PORT:8080}") int apiPort,
      @Value("${TF_REDIS_PORT:6379}") int redisPort,
      @Value("${TF_CONTROL_PORTS:}") String controlPorts) {
    return NetworkBoundary.guard(jdbc, apiPort, redisPort, controlPorts);
  }

  @Bean(destroyMethod = "close")
  SafeHttpExecutor httpExecutor(
      JsonMapper json,
      TargetGuard guard,
      @Value("${TF_RESPONSE_MAX_BYTES:1048576}") int responseBytes,
      @Value("${TF_CONNECT_TIMEOUT_MS:3000}") int connectMs,
      @Value("${TF_HTTP_CONCURRENCY:4}") int concurrent) {
    return new SafeHttpExecutor(json, guard, responseBytes, connectMs, concurrent);
  }
}
