package com.testflow.core.execution;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DeploymentBoundaryTest {
  @Test
  void blocksDeployedPlatformHostsEvenWithExplicitAuthorization() {
    var guard = NetworkBoundary.guard("jdbc:mysql://mysql:3306/testflow", 8080, 6379, "18081");
    for (String host : List.of("api", "worker", "nginx", "kafka")) {
      var uri = URI.create("http://" + host + ":8088");
      assertThatThrownBy(
              () ->
                  guard.authorize(
                      uri,
                      List.of(new ExecutionSnapshot.TargetRule(1L, "http", host, 8088, "PRIVATE"))))
          .isInstanceOf(ExecutionFailure.class);
    }
  }

  @Test
  void deniesPrivatePublishedControlPortButKeepsAuthorizedDemoReachable() throws Exception {
    var guard = NetworkBoundary.guard("jdbc:mysql://mysql:3306/testflow", 8080, 6379, "18081");
    var control =
        new ExecutionSnapshot.TargetRule(1L, "http", "host.docker.internal", 18081, "PRIVATE");
    assertThatThrownBy(
            () ->
                guard.validate(
                    control, new InetAddress[] {InetAddress.getByName("192.168.65.254")}))
        .isInstanceOf(ExecutionFailure.class);
    guard.validate(
        new ExecutionSnapshot.TargetRule(1L, "http", "demo-target", 8088, "PRIVATE"),
        new InetAddress[] {InetAddress.getByName("172.25.0.8")});
    for (String invalid : List.of("0", "65536", "bad"))
      assertThatThrownBy(
              () -> NetworkBoundary.guard("jdbc:mysql://mysql/testflow", 8080, 6379, invalid))
          .isInstanceOf(IllegalArgumentException.class);
  }
}
