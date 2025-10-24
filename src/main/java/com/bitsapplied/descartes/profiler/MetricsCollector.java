package com.bitsapplied.descartes.profiler;

/**
 * Optional interface for collecting profiler metrics.
 * <p>
 * Implement this interface to integrate with your application's metrics system
 * (e.g., Prometheus, Micrometer, Dropwizard Metrics).
 */
public interface MetricsCollector {

  /**
   * Increment a counter metric.
   *
   * @param name the metric name
   */
  void incrementCounter(String name);

  /**
   * Record a timing metric.
   *
   * @param name       the metric name
   * @param durationMs the duration in milliseconds
   */
  void recordTiming(String name, long durationMs);

  /**
   * Set a gauge metric.
   *
   * @param name  the metric name
   * @param value the gauge value
   */
  void setGauge(String name, double value);

  /**
   * No-op implementation for convenience.
   */
  public static MetricsCollector NOOP = new MetricsCollector() {
    @Override
    public void incrementCounter(String name) {
    }

    @Override
    public void recordTiming(String name, long durationMs) {
    }

    @Override
    public void setGauge(String name, double value) {
    }
  };
}
