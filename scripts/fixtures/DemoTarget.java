import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/** Synthetic local-only fixture. Run with Java 21 source launcher; never use as a real service. */
public class DemoTarget {
  private static final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
  private static final AtomicInteger mutations = new AtomicInteger();
  private static final AtomicInteger parallelActive = new AtomicInteger(),
      parallelPeak = new AtomicInteger();
  private static final Map<String, ParallelGroup> parallelGroups = new ConcurrentHashMap<>();
  private static final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();

  private static final class ParallelGroup {
    final AtomicInteger active = new AtomicInteger(),
        peak = new AtomicInteger(),
        entered = new AtomicInteger(),
        finished = new AtomicInteger();
    final Map<String, Integer> arrivals = new ConcurrentHashMap<>(),
        completions = new ConcurrentHashMap<>();

    Map<String, Object> snapshot() {
      return Map.of(
          "active",
          active.get(),
          "peak",
          peak.get(),
          "entered",
          entered.get(),
          "finished",
          finished.get(),
          "arrivals",
          Map.copyOf(arrivals),
          "completions",
          Map.copyOf(completions));
    }
  }

  private static final Set<String> tokens = ConcurrentHashMap.newKeySet();
  private static final Map<Integer, String> orders = new ConcurrentHashMap<>();
  private static final AtomicInteger orderIds = new AtomicInteger(1000);

  public static void main(String[] args) throws Exception {
    int port = args.length == 0 ? 8088 : Integer.parseInt(args[0]);
    String bind = System.getenv().getOrDefault("TF_DEMO_BIND_ADDRESS", "127.0.0.1");
    HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
    var threads =
        new ThreadPoolExecutor(
            8,
            8,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32),
            Thread.ofPlatform().daemon().factory());
    server.setExecutor(threads);
    server.createContext(
        "/",
        exchange -> {
          try {
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            if (path.equals("/parallel/stats")) {
              var groups = new TreeMap<String, Object>();
              parallelGroups.forEach((name, value) -> groups.put(name, value.snapshot()));
              send(
                  exchange,
                  200,
                  Map.of(
                      "synthetic",
                      true,
                      "active",
                      parallelActive.get(),
                      "peak",
                      parallelPeak.get(),
                      "groups",
                      groups));
              return;
            }
            if (path.equals("/parallel/release")) {
              String prefix = query.getOrDefault("group", "") + ":";
              String key = query.get("key");
              if (key == null)
                gates.forEach(
                    (name, latch) -> {
                      if (name.startsWith(prefix)) latch.countDown();
                    });
              else
                gates.computeIfAbsent(prefix + key, ignored -> new CountDownLatch(1)).countDown();
              send(exchange, 200, Map.of("synthetic", true, "released", true));
              return;
            }
            if (path.equals("/parallel/work")) {
              String name = query.getOrDefault("group", "demo"),
                  item = query.getOrDefault("item", UUID.randomUUID().toString());
              var group = parallelGroups.computeIfAbsent(name, ignored -> new ParallelGroup());
              int active = parallelActive.incrementAndGet();
              parallelPeak.accumulateAndGet(active, Math::max);
              group.peak.accumulateAndGet(group.active.incrementAndGet(), Math::max);
              group.arrivals.put(item, group.entered.incrementAndGet());
              try {
                if (query.containsKey("gate"))
                  gates
                      .computeIfAbsent(
                          name + ":" + query.get("gate"), ignored -> new CountDownLatch(1))
                      .await(20, TimeUnit.SECONDS);
                Thread.sleep(delay(query, 100));
                group.completions.put(item, group.finished.incrementAndGet());
                send(
                    exchange,
                    "500".equals(query.get("status")) ? 500 : 200,
                    Map.of("synthetic", true, "data", Map.of("id", 42, "item", item)));
              } finally {
                group.active.decrementAndGet();
                parallelActive.decrementAndGet();
              }
              return;
            }
            if (path.equals("/stats")) {
              Map<String, Object> counts = new TreeMap<>();
              requests.forEach((name, count) -> counts.put(name, count.get()));
              send(
                  exchange,
                  200,
                  Map.of("synthetic", true, "mutations", mutations.get(), "requests", counts));
              return;
            }
            requests.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
            if (path.equals("/flow/login")) {
              String token = "synthetic-" + UUID.randomUUID();
              tokens.add(token);
              send(
                  exchange,
                  200,
                  Map.of("synthetic", true, "data", Map.of("token", token, "quantity", 2)));
              return;
            }
            if (path.equals("/flow/missing")) {
              send(exchange, 200, Map.of("synthetic", true, "data", Map.of()));
              return;
            }
            if (path.startsWith("/flow/orders")) {
              String auth =
                  Optional.ofNullable(exchange.getRequestHeaders().getFirst("Authorization"))
                      .orElse("");
              String token = auth.startsWith("Bearer ") ? auth.substring(7) : "";
              if (!tokens.contains(token)) {
                send(exchange, 401, Map.of("synthetic", true));
                return;
              }
              if (path.equals("/flow/orders") && exchange.getRequestMethod().equals("POST")) {
                String input =
                    new String(
                        exchange.getRequestBody().readNBytes(1048577), StandardCharsets.UTF_8);
                if (input.length() > 1048576
                    || !input.matches("(?s).*\"quantity\"\\s*:\\s*2\\s*[,}].*")) {
                  send(exchange, 400, Map.of("synthetic", true));
                  return;
                }
                int id = orderIds.incrementAndGet();
                orders.put(id, token);
                send(
                    exchange,
                    200,
                    Map.of("synthetic", true, "data", Map.of("id", id, "quantity", 2)));
                return;
              }
              try {
                int id = Integer.parseInt(path.substring("/flow/orders/".length()));
                if (!token.equals(orders.get(id))) {
                  send(exchange, 404, Map.of("synthetic", true));
                  return;
                }
                send(
                    exchange,
                    200,
                    Map.of("synthetic", true, "data", Map.of("id", id, "quantity", 2)));
                return;
              } catch (RuntimeException ex) {
                send(exchange, 400, Map.of("synthetic", true));
                return;
              }
            }
            if (path.equals("/drop")) return;
            if (path.equals("/mutate")) {
              mutations.incrementAndGet();
              Thread.sleep(delay(query, 5000));
            }
            if (path.equals("/delay")) Thread.sleep(delay(query, 5000));
            if (path.equals("/redirect")) {
              exchange
                  .getResponseHeaders()
                  .set("Location", "http://127.0.0.1:" + port + "/followed");
              send(exchange, 302, Map.of("synthetic", true));
              return;
            }
            if (path.equals("/large")) {
              var output = new ByteArrayOutputStream();
              try (var gzip = new GZIPOutputStream(output)) {
                gzip.write(
                    ("{\"synthetic\":true,\"large\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
              }
              byte[] bytes = output.toByteArray();
              exchange.getResponseHeaders().set("Content-Encoding", "gzip");
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
              return;
            }
            if (path.equals("/stream")) {
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, 0);
              exchange.getResponseBody().write('{');
              exchange.getResponseBody().flush();
              for (int i = 0; i < 300; i++) {
                Thread.sleep(30);
                exchange.getResponseBody().write(' ');
                exchange.getResponseBody().flush();
              }
              return;
            }
            if (path.equals("/echo")) {
              byte[] input = exchange.getRequestBody().readNBytes(1048577);
              if (input.length > 1048576) {
                send(exchange, 413, Map.of("synthetic", true));
                return;
              }
              send(
                  exchange,
                  200,
                  Map.of(
                      "synthetic",
                      true,
                      "data",
                      Map.of(
                          "id",
                          42,
                          "marker",
                          query.getOrDefault("marker", ""),
                          "body",
                          new String(input, StandardCharsets.UTF_8),
                          "authorization",
                          Optional.ofNullable(
                                  exchange.getRequestHeaders().getFirst("Authorization"))
                              .orElse(""))));
              return;
            }
            send(
                exchange,
                path.equals("/failed") ? 500 : 200,
                Map.of(
                    "synthetic",
                    true,
                    "data",
                    Map.of("id", 42, "message", "TestFlow local fixture")));
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          } catch (IOException ignored) {
            /* A cancelled/expired request may close the peer connection. */
          } finally {
            exchange.close();
          }
        });
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  server.stop(0);
                  threads.shutdownNow();
                }));
    server.start();
    System.out.println(
        "Synthetic DemoTarget ready at " + bind + ":" + port + "; no production data.");
  }

  private static long delay(Map<String, String> query, long fallback) {
    try {
      return Math.max(
          0, Math.min(30000, Long.parseLong(query.getOrDefault("ms", Long.toString(fallback)))));
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> values = new LinkedHashMap<>();
    if (raw != null)
      for (String pair : raw.split("&")) {
        String[] item = pair.split("=", 2);
        values.put(
            URLDecoder.decode(item[0], StandardCharsets.UTF_8),
            URLDecoder.decode(item.length > 1 ? item[1] : "", StandardCharsets.UTF_8));
      }
    return values;
  }

  private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, Object value)
      throws IOException {
    byte[] body = json(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }

  private static String json(Object value) {
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    if (value instanceof Map<?, ?> map)
      return "{"
          + String.join(
              ",",
              map.entrySet().stream()
                  .map(entry -> json(entry.getKey().toString()) + ":" + json(entry.getValue()))
                  .toList())
          + "}";
    if (value == null) return "null";
    StringBuilder text = new StringBuilder("\"");
    for (char c : value.toString().toCharArray()) {
      switch (c) {
        case '\\' -> text.append("\\\\");
        case '"' -> text.append("\\\"");
        case '\n' -> text.append("\\n");
        case '\r' -> text.append("\\r");
        case '\t' -> text.append("\\t");
        default -> {
          if (c < 32) text.append(String.format("\\u%04x", (int) c));
          else text.append(c);
        }
      }
    }
    return text.append('"').toString();
  }
}
