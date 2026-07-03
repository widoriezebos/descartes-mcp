package com.bitsapplied.descartes.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.settings.Setting;

class ToolExecutorsTest {

  private static final String VIRTUAL_THREADS_ENABLED_KEY = Setting.TOOLS_EXECUTOR_VIRTUAL_THREADS_ENABLED.key();

  private Map<String, Object> context;

  @BeforeEach
  void setUp() {
    context = new HashMap<>();
    System.clearProperty(VIRTUAL_THREADS_ENABLED_KEY);
  }

  @AfterEach
  void tearDown() {
    ToolExecutors.shutdownSharedExecutor(context);
    System.clearProperty(VIRTUAL_THREADS_ENABLED_KEY);
  }

  @Test
  void getSharedExecutorReusesActiveExecutor() {
    ExecutorService first = ToolExecutors.getSharedExecutor(context);
    ExecutorService second = ToolExecutors.getSharedExecutor(context);

    assertSame(first, second);
  }

  @Test
  void defaultExecutorRunsTasksOnVirtualThreads() throws Exception {
    assumeFalse(Boolean.parseBoolean(System.getenv(ToolExecutors.FORCE_PLATFORM_THREADS_ENV)),
        ToolExecutors.FORCE_PLATFORM_THREADS_ENV + " forces the platform-thread fallback");

    ExecutorService executor = ToolExecutors.getSharedExecutor(context);

    boolean virtual = executor.submit(() -> Thread.currentThread().isVirtual()).get(5, TimeUnit.SECONDS);

    assertTrue(virtual);
  }

  @Test
  void settingCanDisableVirtualThreadsForSharedExecutor() throws Exception {
    System.setProperty(VIRTUAL_THREADS_ENABLED_KEY, "false");
    ExecutorService executor = ToolExecutors.getSharedExecutor(context);

    ThreadSnapshot thread = executor.submit(
        () -> new ThreadSnapshot(Thread.currentThread().getName(), Thread.currentThread().isVirtual(),
            Thread.currentThread().isDaemon()))
        .get(5, TimeUnit.SECONDS);

    assertFalse(thread.virtual());
    assertTrue(thread.daemon());
    assertTrue(thread.name().startsWith("descartes-tool-"));
  }

  private record ThreadSnapshot(String name, boolean virtual, boolean daemon) {
  }
}
