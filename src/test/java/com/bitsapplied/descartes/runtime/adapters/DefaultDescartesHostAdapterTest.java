package com.bitsapplied.descartes.runtime.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.profiler.MetricsCollector;
import com.bitsapplied.descartes.profiler.ProfilerListener;
import com.bitsapplied.descartes.profiler.ProfilerSettings;
import com.bitsapplied.descartes.profiler.model.ProfileSnapshot;
import com.bitsapplied.descartes.runtime.DescartesHost;

class DefaultDescartesHostAdapterTest {

  @Test
  void builderAppliesProvidedComponents() {
    ProfilerSettings settings = ProfilerSettings.builder().build();
    ProfilerListener listener = new ProfilerListener() {
      @Override
      public void onProfilingStarted(String profileId) {
      }

      @Override
      public void onProfilingStopped(String profileId, ProfileSnapshot snapshot) {
      }

      @Override
      public void onProfilingError(String profileId, Exception error) {
      }
    };
    MetricsCollector collector = new MetricsCollector() {
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

    AtomicBoolean enabledCalled = new AtomicBoolean(false);

    DefaultDescartesHostAdapter adapter = DefaultDescartesHostAdapter.builder().withProfilerSettings(settings)
        .withProfilerListener(listener).withMetricsCollector(collector).withProfilerEnabledConsumer(enabledCalled::set)
        .withSharedContext(Map.of("hello", "world")).build();

    DescartesHost.ProfilerIntegration integration = adapter.profiler();
    assertThat(integration.settings()).isSameAs(settings);
    assertThat(integration.listener()).isSameAs(listener);
    assertThat(integration.metrics()).isSameAs(collector);

    integration.enabledStateConsumer().accept(true);
    assertThat(enabledCalled).isTrue();

    Map<String, Object> context = adapter.sharedContext();
    assertThat(context).containsEntry("hello", "world");
    assertThatThrownBy(() -> context.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
  }
}
