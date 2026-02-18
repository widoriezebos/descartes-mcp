package com.bitsapplied.descartes.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.profiler.MetricsCollector;
import com.bitsapplied.descartes.profiler.ProfilerListener;
import com.bitsapplied.descartes.profiler.ProfilerSettings;

/**
 * Host contract for embedding Descartes into an application.
 *
 * <p>
 * Implementations expose the minimum integration points required by the core
 * runtime: profiler configuration &amp; telemetry, debugger lifecycle hooks,
 * and an optional shared context map that is passed through to MCP tools and
 * resources.
 * </p>
 */
public interface DescartesHost {

  /**
   * Provides profiler integration details. Implementations must never return
   * {@code null}.
   *
   * @return profiler integration descriptor
   */
  ProfilerIntegration profiler();

  /**
   * Provides debugger integration details. The default implementation returns a
   * no-op descriptor so hosts only need to override when customisation is
   * required.
   *
   * @return debugger integration descriptor
   */
  default DebuggerIntegration debugger() {
    return DebuggerIntegration.defaults();
  }

  /**
   * Optional shared context made available to MCP tools/resources via the
   * runtime. Defaults to an immutable empty map.
   *
   * @return shared context map
   */
  default Map<String, Object> sharedContext() {
    return Map.of();
  }

  /**
   * Profiler integration descriptor.
   */
  interface ProfilerIntegration {

    /**
     * Supplies profiler settings. Called lazily on first profiler access.
     *
     * @return profiler settings
     */
    ProfilerSettings settings();

    /**
     * Profiler listener used to surface lifecycle events to the host.
     *
     * @return profiler listener, defaults to {@link ProfilerListener#NOOP}
     */
    default ProfilerListener listener() {
      return ProfilerListener.NOOP;
    }

    /**
     * Metrics collector that receives profiler telemetry.
     *
     * @return metrics collector, defaults to {@link MetricsCollector#NOOP}
     */
    default MetricsCollector metrics() {
      return MetricsCollector.NOOP;
    }

    /**
     * Callback invoked whenever the profiler enabled state toggles via
     * {@link com.bitsapplied.descartes.profiler.ProfilerService#setEnabled(boolean)}.
     *
     * @return enabled state consumer, defaults to a no-op
     */
    default Consumer<Boolean> enabledStateConsumer() {
      return _enabled -> {
      };
    }
  }

  /**
   * Debugger integration descriptor.
   */
  interface DebuggerIntegration {

    /**
     * Allows hosts to supply their own debugger executor implementation. If this
     * supplier returns {@code null} or no supplier is provided, the runtime will
     * create a standard {@link DebuggerExecutor}.
     *
     * @return supplier for custom executors, defaults to {@code null}
     */
    default Supplier<DebuggerExecutor> executorSupplier() {
      return null;
    }

    /**
     * Allows hosts to supply a pre-configured debugger service. If not provided the
     * runtime instantiates a vanilla {@link DebuggerService}.
     *
     * @return supplier for custom debugger service instances, defaults to
     *         {@code null}
     */
    default Supplier<DebuggerService> serviceSupplier() {
      return null;
    }

    /**
     * Optional shutdown hook executed after the runtime disposes debugger
     * resources.
     *
     * @return shutdown callback, defaults to a no-op
     */
    default Runnable onShutdown() {
      return () -> {
      };
    }

    static DebuggerIntegration defaults() {
      return new DebuggerIntegration() {
      };
    }
  }

  /**
   * Utility base class allowing hosts to focus on supplying a small set of
   * overrides.
   */
  abstract class Base implements DescartesHost {
    @Override
    public ProfilerIntegration profiler() {
      return Objects.requireNonNull(profilerIntegration(), "profilerIntegration");
    }

    protected abstract ProfilerIntegration profilerIntegration();

    @Override
    public DebuggerIntegration debugger() {
      return DescartesHost.super.debugger();
    }

    @Override
    public Map<String, Object> sharedContext() {
      return DescartesHost.super.sharedContext();
    }
  }
}
