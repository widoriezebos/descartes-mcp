package com.bitsapplied.descartes.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.security.EnvironmentDetector.Environment;

/**
 * Tests for EnvironmentDetector.
 */
public class EnvironmentDetectorTest {

  @AfterEach
  public void cleanup() {
    // Clean up system properties set during tests
    System.clearProperty("descartes.environment");
    System.clearProperty("env");
    System.clearProperty("environment");
  }

  @Test
  public void testEnvironmentParsing() {
    assertEquals(Environment.DEVELOPMENT, Environment.parse("development"));
    assertEquals(Environment.DEVELOPMENT, Environment.parse("dev"));
    assertEquals(Environment.STAGING, Environment.parse("staging"));
    assertEquals(Environment.STAGING, Environment.parse("stage"));
    assertEquals(Environment.PRODUCTION, Environment.parse("production"));
    assertEquals(Environment.PRODUCTION, Environment.parse("prod"));
    assertEquals(Environment.TEST, Environment.parse("test"));
    assertEquals(Environment.TEST, Environment.parse("testing"));
    assertEquals(Environment.UNKNOWN, Environment.parse("invalid"));
    assertEquals(Environment.UNKNOWN, Environment.parse(null));
    assertEquals(Environment.UNKNOWN, Environment.parse(""));
  }

  @Test
  public void testEnvironmentCaseInsensitive() {
    assertEquals(Environment.DEVELOPMENT, Environment.parse("DEVELOPMENT"));
    assertEquals(Environment.DEVELOPMENT, Environment.parse("Dev"));
    assertEquals(Environment.PRODUCTION, Environment.parse("PROD"));
    assertEquals(Environment.STAGING, Environment.parse("STAGE"));
  }

  @Test
  public void testDetectFromContext() {
    Map<String, Object> context = new HashMap<>();
    context.put("descartes.environment", "development");

    Environment env = EnvironmentDetector.detectEnvironment(context);
    assertEquals(Environment.DEVELOPMENT, env);
  }

  @Test
  public void testDetectFromSystemProperty() {
    System.setProperty("descartes.environment", "production");

    Environment env = EnvironmentDetector.detectEnvironment(null);
    assertEquals(Environment.PRODUCTION, env);
  }

  @Test
  public void testDetectFromAlternativeSystemProperties() {
    System.setProperty("env", "staging");

    Environment env = EnvironmentDetector.detectEnvironment(null);
    assertEquals(Environment.STAGING, env);
  }

  @Test
  public void testContextTakesPrecedence() {
    Map<String, Object> context = new HashMap<>();
    context.put("descartes.environment", "development");
    System.setProperty("descartes.environment", "production");

    Environment env = EnvironmentDetector.detectEnvironment(context);
    assertEquals(Environment.DEVELOPMENT, env); // Context should win
  }

  @Test
  public void testDefaultToProductionWhenUnknown() {
    // No context, no system properties set
    Environment env = EnvironmentDetector.detectEnvironment(null);
    assertEquals(Environment.PRODUCTION, env); // Should default to production (fail-safe)
  }

  @Test
  public void testIsProduction() {
    assertTrue(Environment.PRODUCTION.isProduction());
    assertTrue(Environment.UNKNOWN.isProduction()); // Fail-safe: unknown is treated as production
    assertFalse(Environment.DEVELOPMENT.isProduction());
    assertFalse(Environment.TEST.isProduction());
    assertFalse(Environment.STAGING.isProduction());
  }

  @Test
  public void testIsDevelopment() {
    assertTrue(Environment.DEVELOPMENT.isDevelopment());
    assertTrue(Environment.TEST.isDevelopment());
    assertFalse(Environment.PRODUCTION.isDevelopment());
    assertFalse(Environment.STAGING.isDevelopment());
    assertFalse(Environment.UNKNOWN.isDevelopment());
  }

  @Test
  public void testIsStrictMode() {
    Map<String, Object> devContext = new HashMap<>();
    devContext.put("descartes.environment", "development");
    assertFalse(EnvironmentDetector.isStrictMode(devContext));

    Map<String, Object> prodContext = new HashMap<>();
    prodContext.put("descartes.environment", "production");
    assertTrue(EnvironmentDetector.isStrictMode(prodContext));

    Map<String, Object> stagingContext = new HashMap<>();
    stagingContext.put("descartes.environment", "staging");
    assertTrue(EnvironmentDetector.isStrictMode(stagingContext));

    // Unknown should be strict (fail-safe)
    assertTrue(EnvironmentDetector.isStrictMode(null));
  }

  @Test
  public void testIsDevelopmentMode() {
    Map<String, Object> devContext = new HashMap<>();
    devContext.put("descartes.environment", "development");
    assertTrue(EnvironmentDetector.isDevelopmentMode(devContext));

    Map<String, Object> testContext = new HashMap<>();
    testContext.put("descartes.environment", "test");
    assertTrue(EnvironmentDetector.isDevelopmentMode(testContext));

    Map<String, Object> prodContext = new HashMap<>();
    prodContext.put("descartes.environment", "production");
    assertFalse(EnvironmentDetector.isDevelopmentMode(prodContext));

    // Unknown should not be development mode (fail-safe)
    assertFalse(EnvironmentDetector.isDevelopmentMode(null));
  }
}
