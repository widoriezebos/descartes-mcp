package com.bitsapplied.descartes.runtime;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.bitsapplied.descartes.profiler.MetricsCollector;
import com.bitsapplied.descartes.profiler.ProfilerListener;
import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.ProfilerSettings;

/**
 * High-level runtime that wires Descartes services to an embedding host via the
 * {@link DescartesHost} contract.
 *
 * <p>
 * The runtime performs lazy initialisation of heavyweight services (debugger,
 * profiler), manages lifecycle/shutdown, and exposes handles that can be shared
 * with MCP servers or other host components.
 * </p>
 */
public final class DescartesRuntime implements AutoCloseable {

  public static final String CONTEXT_KEY_RUNTIME = "descartes.runtime";
  public static final String CONTEXT_KEY_PROFILER = "descartes.profiler";
  public static final String CONTEXT_KEY_DEBUGGER = "descartes.debugger";

  private static final Logger logger = LogManager.getLogger(DescartesRuntime.class);

  private final DescartesHost host;
  private final ProfilerHandle profilerHandle;
  private final DebuggerHandle debuggerHandle;
  private final Map<String, Object> sharedContext;
  private volatile boolean closed;

  private DescartesRuntime(DescartesHost host) {
    this.host = Objects.requireNonNull(host, "host");
    this.profilerHandle = new ProfilerHandle(host.profiler());
    this.debuggerHandle = new DebuggerHandle(host.debugger());
    this.sharedContext = new ConcurrentHashMap<>(Objects.requireNonNull(host.sharedContext(), "sharedContext"));
    this.sharedContext.putIfAbsent(CONTEXT_KEY_RUNTIME, this);
    this.sharedContext.putIfAbsent(CONTEXT_KEY_PROFILER, profilerHandle);
    this.sharedContext.putIfAbsent(CONTEXT_KEY_DEBUGGER, debuggerHandle);
  }

  /**
   * Creates a new runtime for the provided host.
   *
   * @param host host integration descriptor
   * @return new runtime instance
   */
  public static DescartesRuntime bootstrap(DescartesHost host) {
    return new DescartesRuntime(host);
  }

  /**
   * Profiler handle.
   *
   * @return profiler runtime handle
   */
  public ProfilerHandle profiler() {
    return profilerHandle;
  }

  /**
   * Debugger handle.
   *
   * @return debugger runtime handle
   */
  public DebuggerHandle debugger() {
    return debuggerHandle;
  }

  /**
   * Shared context map (read-only view).
   *
   * @return immutable view of the shared context
   */
  public Map<String, Object> sharedContext() {
    return Collections.unmodifiableMap(sharedContext);
  }

  /**
   * Registers runtime-managed entries into the provided target map. Useful for
   * hosts that already maintain their own context structures.
   *
   * @param target map to populate
   */
  public void contributeTo(Map<String, Object> target) {
    Objects.requireNonNull(target, "target");
    target.putIfAbsent(CONTEXT_KEY_RUNTIME, this);
    target.putIfAbsent(CONTEXT_KEY_PROFILER, profilerHandle);
    target.putIfAbsent(CONTEXT_KEY_DEBUGGER, debuggerHandle);
    host.sharedContext().forEach(target::putIfAbsent);
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    RuntimeException profilerFailure = null;

    try {
      profilerHandle.shutdown();
    } catch (RuntimeException e) {
      profilerFailure = e;
    }

    try {
      debuggerHandle.shutdown();
    } catch (RuntimeException e) {
      if (profilerFailure != null) {
        profilerFailure.addSuppressed(e);
      } else {
        profilerFailure = e;
      }
    }

    if (profilerFailure != null) {
      throw profilerFailure;
    }
  }

  /**
   * Profiler runtime facade. Lazily instantiates {@link ProfilerService} and
   * forwards lifecycle events to the host.
   */
  public static final class ProfilerHandle {
    private final DescartesHost.ProfilerIntegration integration;
    private volatile HostAwareProfilerService delegate;
    private volatile Boolean pendingEnabled;
    private final Object lock = new Object();

    private ProfilerHandle(DescartesHost.ProfilerIntegration integration) {
      this.integration = Objects.requireNonNull(integration, "integration");
    }

    /**
     * Returns the profiler service, creating it lazily when first requested.
     *
     * @return profiler service
     */
    public ProfilerService service() {
      ProfilerService svc = delegate;
      if (svc == null) {
        synchronized (lock) {
          svc = delegate;
          if (svc == null) {
            svc = createService();
            delegate = (HostAwareProfilerService) svc;
            logger.info("Descartes profiler initialised");
          }
        }
      }
      return svc;
    }

    /**
     * @return the profiler settings supplied by the host.
     */
    public ProfilerSettings settings() {
      return integration.settings();
    }

    /**
     * Updates the profiler enabled state. Applies immediately if the service has
     * been initialised, otherwise the value is remembered and applied when the
     * service is created.
     *
     * @param enabled desired enabled state
     */
    public void setEnabled(boolean enabled) {
      pendingEnabled = enabled;
      HostAwareProfilerService svc = delegate;
      if (svc != null) {
        svc.setEnabledFlag(enabled);
      } else {
        notifyEnabledConsumer(enabled);
      }
    }

    /**
     * Indicates whether the profiler infrastructure has been created.
     *
     * @return true when the profiler service exists
     */
    public boolean isInitialised() {
      return delegate != null;
    }

    /**
     * Requests profiler shutdown if initialised.
     */
    void shutdown() {
      HostAwareProfilerService svc = delegate;
      if (svc != null) {
        try {
          svc.shutdown();
        } catch (Exception e) {
          logger.warn("Failed to shutdown profiler cleanly: {}", e.getMessage());
          throw new RuntimeException("Failed to shutdown profiler", e);
        } finally {
          delegate = null;
        }
      }
      pendingEnabled = null;
    }

    private HostAwareProfilerService createService() {
      ProfilerSettings settings = Objects.requireNonNull(integration.settings(), "settings");
      ProfilerListener listener = Objects.requireNonNull(integration.listener(), "listener");
      MetricsCollector metrics = Objects.requireNonNull(integration.metrics(), "metrics");
      Consumer<Boolean> enabledConsumer = Objects.requireNonNull(integration.enabledStateConsumer(),
          "enabledStateConsumer");
      HostAwareProfilerService service = new HostAwareProfilerService(settings, listener, metrics, enabledConsumer);
      Boolean override = pendingEnabled;
      if (override != null && override.booleanValue() != service.isEnabled()) {
        service.initialiseEnabled(override);
      }
      return service;
    }

    private void notifyEnabledConsumer(boolean enabled) {
      try {
        integration.enabledStateConsumer().accept(enabled);
      } catch (RuntimeException e) {
        logger.warn("Profiler enabled-state callback failed: {}", e.getMessage());
      }
    }
  }

  /**
   * Debugger runtime facade. Lazily instantiates {@link DebuggerService} and the
   * supporting {@link DebuggerExecutor}.
   */
  public static final class DebuggerHandle {
    private final DescartesHost.DebuggerIntegration integration;
    private volatile DebuggerService service;
    private volatile DebuggerExecutor executor;
    private final Object lock = new Object();

    private DebuggerHandle(DescartesHost.DebuggerIntegration integration) {
      this.integration = Objects.requireNonNull(integration, "integration");
    }

    /**
     * Returns the debugger service, initialising the executor and service on first
     * call.
     *
     * @return debugger service
     */
    public DebuggerService service() {
      ensureInitialised();
      return service;
    }

    /**
     * Returns the debugger executor associated with the runtime.
     *
     * @return debugger executor
     */
    public DebuggerExecutor executor() {
      ensureInitialised();
      return executor;
    }

    /**
     * Indicates whether debugger infrastructure has been created.
     *
     * @return true when both service and executor exist
     */
    public boolean isInitialised() {
      return service != null && executor != null;
    }

    /**
     * Requests debugger shutdown if initialised.
     */
    void shutdown() {
      DebuggerService svc = service;
      DebuggerExecutor exec = executor;
      if (svc == null && exec == null) {
        return;
      }

      try {
        if (svc != null) {
          try {
            svc.stop();
          } catch (DebuggerException e) {
            logger.warn("Debugger stop failed during shutdown: {}", e.getMessage());
          }
        }
      } finally {
        if (exec != null && !exec.isShutdown()) {
          exec.shutdown();
        }
        service = null;
        executor = null;
        try {
          integration.onShutdown().run();
        } catch (Exception hookEx) {
          logger.warn("Debugger shutdown hook threw an exception: {}", hookEx.getMessage());
        }
      }
    }

    private void ensureInitialised() {
      if (service == null || executor == null) {
        synchronized (lock) {
          if (service == null || executor == null) {
            executor = createExecutor();
            service = createService();
            logger.info("Descartes debugger initialised");
          }
        }
      }
    }

    private DebuggerExecutor createExecutor() {
      Supplier<DebuggerExecutor> supplier = integration.executorSupplier();
      if (supplier != null) {
        DebuggerExecutor custom = Objects.requireNonNull(supplier.get(),
            "executorSupplier returned null DebuggerExecutor");
        return custom;
      }
      return new DebuggerExecutor();
    }

    private DebuggerService createService() {
      Supplier<DebuggerService> supplier = integration.serviceSupplier();
      if (supplier != null) {
        DebuggerService custom = Objects.requireNonNull(supplier.get(),
            "serviceSupplier returned null DebuggerService");
        return custom;
      }
      return new DebuggerService();
    }
  }

  /**
   * Host-aware profiler service that notifies the enabled-state consumer whenever
   * the flag toggles.
   */
  private static final class HostAwareProfilerService extends ProfilerService {
    private volatile boolean enabled;
    private final Consumer<Boolean> enabledConsumer;

    private HostAwareProfilerService(ProfilerSettings settings, ProfilerListener listener, MetricsCollector metrics,
        Consumer<Boolean> enabledConsumer) {
      super(settings, listener, metrics);
      this.enabled = settings.isEnabled();
      this.enabledConsumer = enabledConsumer;
    }

    @Override
    public boolean isEnabled() {
      return enabled;
    }

    void setEnabledFlag(boolean enabled) {
      this.enabled = enabled;
      try {
        enabledConsumer.accept(enabled);
      } catch (RuntimeException e) {
        logger.warn("Profiler enabled-state callback failed: {}", e.getMessage());
      }
    }

    void initialiseEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }
}
