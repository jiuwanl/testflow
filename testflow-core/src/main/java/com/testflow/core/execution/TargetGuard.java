package com.testflow.core.execution;

import com.testflow.core.DefinitionValidator;
import java.net.*;
import java.util.*;

/** Exact authority authorization plus address classification; no network I/O in this class. */
public final class TargetGuard {
  private static final Set<String> FORBIDDEN_HOSTS =
      Set.of(
          "api",
          "worker",
          "nginx",
          "kafka",
          "mysql",
          "redis",
          "metadata",
          "metadata.google.internal",
          "metadata.azure.internal",
          "instance-data",
          "kubernetes.default.svc");
  private final Set<Integer> protectedPrivatePorts;

  public TargetGuard(Set<Integer> protectedPrivatePorts) {
    this.protectedPrivatePorts = Set.copyOf(protectedPrivatePorts);
  }

  public static int port(URI uri) {
    return uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
  }

  public static String host(URI uri) {
    if (uri.getHost() == null) throw new ExecutionFailure("TARGET_INVALID");
    return DefinitionValidator.host(uri.getHost());
  }

  public ExecutionSnapshot.TargetRule authorize(URI uri, List<ExecutionSnapshot.TargetRule> rules) {
    String host = host(uri);
    if (!Set.of("http", "https").contains(uri.getScheme())
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || FORBIDDEN_HOSTS.contains(host)
        || Set.of(2375, 2376).contains(port(uri))) throw new ExecutionFailure("TARGET_BLOCKED");
    var rule =
        rules.stream()
            .filter(
                candidate ->
                    candidate.host().equals(host)
                        && candidate.scheme().equals(uri.getScheme())
                        && candidate.port() == port(uri))
            .findFirst()
            .orElseThrow(() -> new ExecutionFailure("TARGET_NOT_AUTHORIZED"));
    if (org.apache.hc.core5.net.InetAddressUtils.isIPv4Address(host)
        || org.apache.hc.core5.net.InetAddressUtils.isIPv6Address(host)) {
      try {
        validate(rule, new InetAddress[] {InetAddress.getByName(host)});
      } catch (UnknownHostException ex) {
        throw new ExecutionFailure("TARGET_INVALID");
      }
    }
    return rule;
  }

  public void validate(ExecutionSnapshot.TargetRule rule, InetAddress[] addresses) {
    if (addresses == null || addresses.length == 0 || addresses.length > 32)
      throw new ExecutionFailure("DNS_INVALID");
    for (InetAddress address : addresses) {
      String scope = scope(address);
      if (!rule.addressScope().equals(scope)
          || (!"PUBLIC".equals(scope) && protectedPrivatePorts.contains(rule.port()))) {
        throw new ExecutionFailure("TARGET_ADDRESS_BLOCKED");
      }
    }
  }

  public String scope(InetAddress address) {
    byte[] b = address.getAddress();
    if (b.length == 16) {
      // Normalize mapped IPv4 even when supplied by a custom resolver as Inet6Address.
      boolean mapped = true;
      for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
      if (mapped && (b[10] & 255) == 255 && (b[11] & 255) == 255) {
        try {
          return scope(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16)));
        } catch (UnknownHostException ex) {
          throw new ExecutionFailure("TARGET_ADDRESS_BLOCKED");
        }
      }
      if (address.isLoopbackAddress()) return "LOOPBACK";
      int first = b[0] & 255, second = b[1] & 255;
      if ((first & 254) == 252) {
        if (is(address, "fd00:ec2::254")) throw new ExecutionFailure("TARGET_ADDRESS_BLOCKED");
        return "PRIVATE";
      }
      if ((first & 224) != 32
          || (first == 32 && second == 2)
          || (first == 32 && second == 1 && b[2] == 0 && b[3] == 0)
          || (first == 32 && second == 1 && (b[2] & 255) == 13 && (b[3] & 255) == 184)) {
        throw new ExecutionFailure("TARGET_ADDRESS_BLOCKED");
      }
      return "PUBLIC";
    }
    if (b.length != 4) throw new ExecutionFailure("TARGET_ADDRESS_BLOCKED");
    int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
    if (a == 0
        || a >= 224
        || (a == 169 && c == 254)
        || (a == 100 && c >= 64 && c <= 127)
        || (a == 192 && (c == 0 || (c == 88 && d == 99)))
        || (a == 198 && (c == 18 || c == 19 || (c == 51 && d == 100)))
        || (a == 203 && c == 0 && d == 113)) throw new ExecutionFailure("TARGET_ADDRESS_BLOCKED");
    if (a == 127) return "LOOPBACK";
    if (a == 10 || (a == 172 && c >= 16 && c <= 31) || (a == 192 && c == 168)) return "PRIVATE";
    return "PUBLIC";
  }

  private boolean is(InetAddress actual, String literal) {
    try {
      return actual.equals(InetAddress.getByName(literal));
    } catch (UnknownHostException ex) {
      return false;
    }
  }
}
