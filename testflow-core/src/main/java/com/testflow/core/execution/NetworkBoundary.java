package com.testflow.core.execution;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

public final class NetworkBoundary {
  private NetworkBoundary() {}

  public static TargetGuard guard(String jdbcUrl, int apiPort, int redisPort) {
    return guard(jdbcUrl, apiPort, redisPort, "");
  }

  public static TargetGuard guard(String jdbcUrl, int apiPort, int redisPort, String controlPorts) {
    var ports = new HashSet<>(Set.of(3306, 6379, 2375, 2376, 9092, 9093, 19092, 29092));
    URI database = URI.create(jdbcUrl.substring("jdbc:".length()));
    ports.add(database.getPort() < 0 ? 3306 : database.getPort());
    ports.add(apiPort);
    ports.add(redisPort);
    for (String value : controlPorts.split(",")) {
      if (value.isBlank()) continue;
      int port = Integer.parseInt(value.trim());
      if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid control port");
      ports.add(port);
    }
    return new TargetGuard(ports);
  }
}
