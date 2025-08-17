package com.bitsapplied.descartes.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class InMemoryAppenderExceptionTest {
  private static final Logger logger = LogManager.getLogger(InMemoryAppenderExceptionTest.class);
  private InMemoryAppender appender;

  @BeforeEach
  public void setUp() {
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    Configuration config = context.getConfiguration();
    appender = (InMemoryAppender) config.getAppender("INMEMORY");
    if (appender != null) {
      appender.clearExceptionBuffer();
    }
  }

  @Test
  public void testExceptionBuffering() {
    if (appender == null) {
      System.out.println("InMemoryAppender not found in configuration. Skipping test.");
      return;
    }

    // Log some exceptions
    try {
      throw new RuntimeException("Test exception 1");
    } catch (Exception e) {
      logger.error("First error occurred", e);
    }

    try {
      throw new IllegalArgumentException("Test exception 2");
    } catch (Exception e) {
      logger.error("Second error occurred", e);
    }

    try {
      throw new NullPointerException("Test exception 3");
    } catch (Exception e) {
      logger.error("Third error occurred", e);
    }

    // Give the appender a moment to process
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      // Ignore
    }

    // Check exception buffer
    List<String> exceptions = appender.getExceptionBuffer();
    assertNotNull(exceptions);
    assertTrue(exceptions.size() >= 3, "Should have at least 3 exceptions");

    // Check last exception
    String lastException = appender.getLastException();
    assertNotNull(lastException);
    assertTrue(lastException.contains("NullPointerException"));
    assertTrue(lastException.contains("Test exception 3"));

    // Check getting last N exceptions
    List<String> lastTwo = appender.getLastExceptions(2);
    assertEquals(2, lastTwo.size());
    assertTrue(lastTwo.get(0).contains("IllegalArgumentException"));
    assertTrue(lastTwo.get(1).contains("NullPointerException"));

    // Test clear
    appender.clearExceptionBuffer();
    assertEquals(0, appender.getExceptionBuffer().size());
    assertNull(appender.getLastException());
  }

  @Test
  public void testExceptionBufferSizeLimits() {
    if (appender == null) {
      System.out.println("InMemoryAppender not found in configuration. Skipping test.");
      return;
    }

    // Set small buffer sizes for testing
    appender.setMaxExceptionBufferSize(5);
    appender.setTruncateExceptionBackTo(3);

    // Log more exceptions than the buffer can hold
    for (int i = 0; i < 10; i++) {
      try {
        throw new RuntimeException("Test exception " + i);
      } catch (Exception e) {
        logger.error("Error number " + i, e);
      }
    }

    // Give the appender a moment to process
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      // Ignore
    }

    // Check that buffer was truncated
    List<String> exceptions = appender.getExceptionBuffer();
    assertTrue(exceptions.size() <= 5, "Buffer should not exceed max size");

    // The most recent exceptions should be retained
    String lastException = appender.getLastException();
    assertNotNull(lastException);
    assertTrue(lastException.contains("Test exception 9"));
  }
}