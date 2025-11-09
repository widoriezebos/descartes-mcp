package com.bitsapplied.descartes.runtime.adapters;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.profiler.MetricsCollector;
import com.bitsapplied.descartes.profiler.ProfilerListener;
import com.bitsapplied.descartes.profiler.ProfilerSettings;
import com.bitsapplied.descartes.runtime.DescartesHost;

/**
 * Convenience adapter that implements {@link DescartesHost} with sensible
 * defaults. Hosts can configure integration points via the fluent builder
 * without needing to provide dedicated wrapper classes.
 */
public final class DefaultDescartesHostAdapter implements DescartesHost {

  private final Supplier<ProfilerSettings> settingsSupplier;
  private final ProfilerListener profilerListener;
  private final MetricsCollector metricsCollector;
  private final Consumer<Boolean> enabledConsumer;
  private final Supplier<DebuggerExecutor> debuggerExecutorSupplier;
  private final Supplier<DebuggerService> debuggerServiceSupplier;
  private final Runnable debuggerShutdownHook;
  private final Map<String, Object> sharedContext;

  private DefaultDescartesHostAdapter(Builder builder) {
    this.settingsSupplier = builder.settingsSupplier;
    this.profilerListener = builder.profilerListener;
    this.metricsCollector = builder.metricsCollector;
    this.enabledConsumer = builder.enabledConsumer;
    this.debuggerExecutorSupplier = builder.debuggerExecutorSupplier;
    this.debuggerServiceSupplier = builder.debuggerServiceSupplier;
    this.debuggerShutdownHook = builder.debuggerShutdownHook;
    this.sharedContext = new ConcurrentHashMap<>(builder.sharedContext);
  }

  /**
   * Creates a new builder.
   *
   * @return builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates an adapter with default configuration. Equivalent to
   * {@code builder().build()}.
   *
   * @return adapter with default behaviour
   */
  public static DefaultDescartesHostAdapter defaults() {
    return builder().build();
  }

  @Override
  public ProfilerIntegration profiler() {
    return new ProfilerIntegration() {
      @Override
      public ProfilerSettings settings() {
        return Objects.requireNonNull(settingsSupplier.get(), "settingsSupplier returned null");
      }

      @Override
      public ProfilerListener listener() {
        return profilerListener;
      }

      @Override
      public MetricsCollector metrics() {
        return metricsCollector;
      }

      @Override
      public Consumer<Boolean> enabledStateConsumer() {
        return enabledConsumer;
      }
    };
  }

  @Override
  public DebuggerIntegration debugger() {
    return new DebuggerIntegration() {
      @Override
      public Supplier<DebuggerExecutor> executorSupplier() {
        return debuggerExecutorSupplier;
      }

      @Override
      public Supplier<DebuggerService> serviceSupplier() {
        return debuggerServiceSupplier;
      }

      @Override
      public Runnable onShutdown() {
        return debuggerShutdownHook;
      }
    };
  }

  @Override
  public Map<String, Object> sharedContext() {
    return Collections.unmodifiableMap(sharedContext);
  }

  /**
   * Fluent builder for {@link DefaultDescartesHostAdapter}.
   */
  public static final class Builder {
    private Supplier<ProfilerSettings> settingsSupplier = () -> ProfilerSettings.builder().build();
    private ProfilerListener profilerListener = ProfilerListener.NOOP;
    private MetricsCollector metricsCollector = MetricsCollector.NOOP;
    private Consumer<Boolean> enabledConsumer = _ -> {
    };
    private Supplier<DebuggerExecutor> debuggerExecutorSupplier;
    private Supplier<DebuggerService> debuggerServiceSupplier;
    private Runnable debuggerShutdownHook = () -> {
    };
    private Map<String, Object> sharedContext = Map.of();

    private Builder() {
    }

    /**
     * Uses a static profiler settings instance.
     *
     * @param settings profiler settings
     * @return builder
     */
    public Builder withProfilerSettings(ProfilerSettings settings) {
      Objects.requireNonNull(settings, "settings");
      this.settingsSupplier = () -> settings;
      return this;
    }

    /**
     * Supplies a profiler settings provider (evaluated on first use).
     *
     * @param supplier settings supplier
     * @return builder
     */
    public Builder withProfilerSettingsSupplier(Supplier<ProfilerSettings> supplier) {
      this.settingsSupplier = Objects.requireNonNull(supplier, "supplier");
      return this;
    }

    /**
     * Registers a profiler listener that relays lifecycle events to the host.
     *
     * @param listener profiler listener
     * @return builder
     */
    public Builder withProfilerListener(ProfilerListener listener) {
      this.profilerListener = Objects.requireNonNull(listener, "listener");
      return this;
    }

    /**
     * Registers a metrics collector for profiler telemetry.
     *
     * @param collector metrics collector
     * @return builder
     */
    public Builder withMetricsCollector(MetricsCollector collector) {
      this.metricsCollector = Objects.requireNonNull(collector, "collector");
      return this;
    }

    /**
     * Registers a callback invoked whenever the profiler enabled state changes.
     *
     * @param consumer enabled state consumer
     * @return builder
     */
    public Builder withProfilerEnabledConsumer(Consumer<Boolean> consumer) {
      this.enabledConsumer = Objects.requireNonNull(consumer, "consumer");
      return this;
    }

    /**
     * Registers a custom debugger executor supplier.
     *
     * @param supplier executor supplier
     * @return builder
     */
    public Builder withDebuggerExecutorSupplier(Supplier<DebuggerExecutor> supplier) {
      this.debuggerExecutorSupplier = Objects.requireNonNull(supplier, "supplier");
      return this;
    }

    /**
     * Registers a custom debugger service supplier.
     *
     * @param supplier debugger service supplier
     * @return builder
     */
    public Builder withDebuggerServiceSupplier(Supplier<DebuggerService> supplier) {
      this.debuggerServiceSupplier = Objects.requireNonNull(supplier, "supplier");
      return this;
    }

    /**
     * Registers a shutdown hook invoked after debugger teardown.
     *
     * @param hook shutdown hook
     * @return builder
     */
    public Builder withDebuggerShutdownHook(Runnable hook) {
      this.debuggerShutdownHook = Objects.requireNonNull(hook, "hook");
      return this;
    }

    /**
     * Adds shared context entries.
     *
     * @param context shared context map
     * @return builder
     */
    public Builder withSharedContext(Map<String, Object> context) {
      this.sharedContext = new ConcurrentHashMap<>(Objects.requireNonNull(context, "context"));
      return this;
    }

    /**
     * Builds the adapter.
     *
     * @return adapter
     */
    public DefaultDescartesHostAdapter build() {
      return new DefaultDescartesHostAdapter(this);
    }
  }
}
