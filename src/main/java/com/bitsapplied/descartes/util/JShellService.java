package com.bitsapplied.descartes.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.execution.LocalExecutionControlProvider;

/**
 * JShell service with clean eval API. Captures stdout/stderr via
 * ConsoleCapture. Binds context variables once at construction. No dynamic
 * injection.
 */
public final class JShellService implements AutoCloseable {

  private static final Logger log = LogManager.getLogger(JShellService.class);

  /** Exposed to JShell user code to bind context. */
  public static volatile Map<String, Object> CTX;

  private final JShell jshell;

  public JShellService(Map<String, Object> context) {
    CTX = Objects.requireNonNull(context, "context");

    // Ensure capture wrappers are installed once for the process.
    ConsoleCapture.installOnce();

    String exactCp = ClassPathHelper.buildExactClassPath(Thread.currentThread().getContextClassLoader());

    this.jshell = JShell.builder().executionEngine(new LocalExecutionControlProvider(), null) // same JVM
        .build();

    if (!exactCp.isBlank()) {
      jshell.addToClasspath(exactCp);
    }

    // Initialize JShell environment with imports and utilities
    initializeJShellEnvironment();
  }

  private void evalInit(String code) {
    var results = jshell.eval(code);
    for (var event : results) {
      if (event.status() != Snippet.Status.VALID) {
        log.error("JShell init failed for: {} - {}", code, event);
      }
    }
  }

  private void initializeJShellEnvironment() {
    log.debug("Initializing JShell environment");

    // Bind context variables
    // First bind the map itself
    evalInit(String.format("java.util.Map<String, Object> context = %s.CTX;", JShellService.class.getName()));

    // Then bind specific context variables if they exist
    if (CTX.containsKey("app.context")) {
      // For applications with a specific context object, expose it directly
      evalInit("Object appContext = context.get(\"app.context\");");
    }
  }

  /**
   * Evaluate code. Splits multi-statement input into complete snippets. Captures
   * stdout/stderr only from the JShell user-code ClassLoader for this eval.
   */
  public EvalResult eval(String code) {
    // Buffers for this eval.
    ConsoleCapture.Buffers buffers = new ConsoleCapture.Buffers(new ByteArrayOutputStream(8 * 1024),
        new ByteArrayOutputStream(4 * 1024));
    String token = ConsoleCapture.register(buffers);
    Instant t0 = Instant.now();

    List<SnippetEvent> allEvents = new ArrayList<>();
    SourceCodeAnalysis sca = jshell.sourceCodeAnalysis();

    try {
      // Activate capture inside the JShell worker.
      jshell.eval("%s.begin(\"%s\");".formatted(ConsoleCapture.class.getName(), token));

      int p = 0;
      final int n = code.length();
      while (p < n) {
        SourceCodeAnalysis.CompletionInfo info = sca.analyzeCompletion(code.substring(p));
        String unit = info.source();
        if (unit == null)
          break;

        switch (info.completeness()) {
        case COMPLETE -> {
          allEvents.addAll(jshell.eval(unit));
          p += unit.length();
        }
        case COMPLETE_WITH_SEMI -> {
          allEvents.addAll(jshell.eval(unit + ";"));
          int consumed = unit.length();
          if (p + consumed < n && code.charAt(p + consumed) == ';')
            consumed++;
          p += consumed;
        }
        case EMPTY -> {
          p += unit.isEmpty() ? 1 : unit.length();
        }
        case CONSIDERED_INCOMPLETE, DEFINITELY_INCOMPLETE -> {
          allEvents.addAll(jshell.eval(code.substring(p)));
          p = n;
        }
        default -> {
          allEvents.addAll(jshell.eval(code.substring(p)));
          p = n;
        }
        }
      }
    } finally {
      jshell.eval("%s.end();".formatted(ConsoleCapture.class.getName()));
      ConsoleCapture.unregister(token);
    }

    Instant t1 = Instant.now();

    String out = buffers.outBuf.toString(StandardCharsets.UTF_8);
    String err = buffers.errBuf.toString(StandardCharsets.UTF_8);

    return new EvalResult(out, err, Collections.unmodifiableList(allEvents), t0, t1);
  }

  @Override
  public void close() {
    try {
      jshell.close();
    } finally {
      // keep CTX as last bound; no dynamic state to clear
    }
  }
}
