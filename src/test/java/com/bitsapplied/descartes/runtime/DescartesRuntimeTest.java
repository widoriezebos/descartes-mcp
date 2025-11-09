package com.bitsapplied.descartes.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.debugger.DebuggerExecutor;
import com.bitsapplied.descartes.profiler.ProfilerService;
import com.bitsapplied.descartes.profiler.ProfilerSettings;
import com.bitsapplied.descartes.runtime.adapters.DefaultDescartesHostAdapter;

class DescartesRuntimeTest {

  @Test
  void profilerIsCreatedLazilyAndNotifiesHostOnEnabledToggle() {
    AtomicInteger settingsCalls = new AtomicInteger();
    AtomicBoolean enabledFlag = new AtomicBoolean(true);

    DefaultDescartesHostAdapter host = DefaultDescartesHostAdapter.builder().withProfilerSettingsSupplier(() -> {
      settingsCalls.incrementAndGet();
      return ProfilerSettings.builder().enabled(true).build();
    }).withProfilerEnabledConsumer(enabledFlag::set).build();

    try (DescartesRuntime runtime = DescartesRuntime.bootstrap(host)) {
      assertThat(runtime.profiler().isInitialised()).isFalse();

      ProfilerService service = runtime.profiler().service();
      assertThat(service).isNotNull();
      assertThat(runtime.profiler().isInitialised()).isTrue();
      assertThat(settingsCalls).hasValue(1);

      runtime.profiler().setEnabled(false);
      assertThat(service.isEnabled()).isFalse();
      assertThat(enabledFlag).isFalse();
    }
  }

  @Test
  void debuggerUsesCustomExecutorAndRunsShutdownHook() {
    AtomicInteger executorCreations = new AtomicInteger();
    AtomicBoolean shutdownHookInvoked = new AtomicBoolean(false);

    DefaultDescartesHostAdapter host = DefaultDescartesHostAdapter.builder()
        .withProfilerSettings(ProfilerSettings.builder().build()).withDebuggerExecutorSupplier(() -> {
          executorCreations.incrementAndGet();
          return new DebuggerExecutor();
        }).withDebuggerShutdownHook(() -> shutdownHookInvoked.set(true)).build();

    DebuggerExecutor executor;
    try (DescartesRuntime runtime = DescartesRuntime.bootstrap(host)) {
      assertThat(runtime.debugger().isInitialised()).isFalse();

      executor = runtime.debugger().executor();
      assertThat(executor).isNotNull();
      assertThat(runtime.debugger().isInitialised()).isTrue();
      assertThat(executorCreations).hasValue(1);
      assertThat(executor.isShutdown()).isFalse();
    }

    assertThat(executorCreations).hasValue(1);
    assertThat(shutdownHookInvoked).isTrue();
    assertThat(executor.isShutdown()).isTrue();
  }

  @Test
  void sharedContextExposesRuntimeHandles() {
    DefaultDescartesHostAdapter host = DefaultDescartesHostAdapter.builder()
        .withSharedContext(Map.of("customKey", "customValue")).build();

    try (DescartesRuntime runtime = DescartesRuntime.bootstrap(host)) {
      Map<String, Object> context = runtime.sharedContext();
      assertThat(context).containsKeys(DescartesRuntime.CONTEXT_KEY_RUNTIME, DescartesRuntime.CONTEXT_KEY_PROFILER,
          DescartesRuntime.CONTEXT_KEY_DEBUGGER, "customKey").containsEntry("customKey", "customValue");
    }
  }
}
