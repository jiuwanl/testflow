package com.testflow.core.execution;

import java.util.Collection;

/** Formula source: PRD §5. Zero denominators are represented by null. */
public record RunMetrics(
    int total, int passed, int failed, int errors, int skipped, int cancelled, int unknown) {
  public static RunMetrics of(Collection<String> states) {
    return new RunMetrics(
        states.size(),
        count(states, "PASSED"),
        count(states, "FAILED"),
        count(states, "ERROR"),
        count(states, "SKIPPED"),
        count(states, "CANCELLED"),
        count(states, "UNKNOWN"));
  }

  private static int count(Collection<String> states, String value) {
    return (int) states.stream().filter(value::equals).count();
  }

  public int known() {
    return passed + failed + errors;
  }

  public int done() {
    return known() + skipped + cancelled + unknown;
  }

  private static Double percent(int part, int total) {
    return total == 0 ? null : Math.round(part * 10000.0 / total) / 100.0;
  }

  public Double progress() {
    return percent(done(), total);
  }

  public Double coverage() {
    return percent(known(), total);
  }

  public Double passRate() {
    return percent(passed, known());
  }

  public Double failureRate() {
    return percent(failed, known());
  }

  public Double errorRate() {
    return percent(errors, known());
  }

  public String outcome(String state) {
    if (!"COMPLETED".equals(state)) return "INCOMPLETE";
    if (total > 0 && passed == total) return "PASSED";
    return failed + errors > 0 ? "FAILED" : "INCOMPLETE";
  }
}
