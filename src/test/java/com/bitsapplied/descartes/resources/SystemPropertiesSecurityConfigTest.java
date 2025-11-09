package com.bitsapplied.descartes.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests for SystemPropertiesSecurityConfig.
 */
public class SystemPropertiesSecurityConfigTest {

  @Test
  public void testForDevelopment() {
    SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.forDevelopment();

    assertTrue(config.isEnabled());
    assertTrue(config.isAllowSensitiveAccess());
    assertFalse(config.isAuditLogging());
    assertFalse(config.isStrictMode());
    assertTrue(config.getAllowedKeys().isEmpty());
    assertTrue(config.getDeniedKeys().isEmpty());
  }

  @Test
  public void testForProduction() {
    SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.forProduction();

    assertTrue(config.isEnabled());
    assertFalse(config.isAllowSensitiveAccess());
    assertTrue(config.isAuditLogging());
    assertTrue(config.isStrictMode());

    // Should have specific allowlist for safe properties
    assertFalse(config.getAllowedKeys().isEmpty());
    assertTrue(config.getAllowedKeys().contains("java.version"));
    assertTrue(config.getAllowedKeys().contains("os.name"));

    // Should have denylist for sensitive patterns
    assertFalse(config.getDeniedKeys().isEmpty());
    assertTrue(config.getDeniedKeys().contains("AWS_*"));
    assertTrue(config.getDeniedKeys().contains("*_PASSWORD"));
  }

  @Test
  public void testForTesting() {
    SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.forTesting();

    assertTrue(config.isEnabled());
    assertTrue(config.isAllowSensitiveAccess());
    assertFalse(config.isAuditLogging());
    assertFalse(config.isStrictMode());
  }

  @Test
  public void testBuilderDefaults() {
    SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.builder().build();

    assertTrue(config.isEnabled());
    assertFalse(config.isAllowSensitiveAccess()); // Secure default
    assertFalse(config.isAuditLogging());
    assertFalse(config.isStrictMode());
  }

  @Test
  public void testBuilderWithCustomSettings() {
    SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.builder().enabled(true)
        .allowSensitiveAccess(true).auditLogging(true).strictMode(false).allowKey("java.*").allowKey("os.name")
        .denyKey("AWS_*").denyKey("*_PASSWORD").addSensitiveKeyword("internal").build();

    assertTrue(config.isEnabled());
    assertTrue(config.isAllowSensitiveAccess());
    assertTrue(config.isAuditLogging());
    assertFalse(config.isStrictMode());

    assertEquals(2, config.getAllowedKeys().size());
    assertTrue(config.getAllowedKeys().contains("java.*"));
    assertTrue(config.getAllowedKeys().contains("os.name"));

    assertEquals(2, config.getDeniedKeys().size());
    assertTrue(config.getDeniedKeys().contains("AWS_*"));
    assertTrue(config.getDeniedKeys().contains("*_PASSWORD"));

    assertEquals(1, config.getCustomSensitiveKeywords().size());
    assertTrue(config.getCustomSensitiveKeywords().contains("internal"));
  }

  @Test
  public void testBuilderWithMultipleKeys() {
    SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.builder()
        .allowKeys(Set.of("java.*", "os.*", "user.timezone")).denyKeys(Set.of("AWS_*", "DB_*", "*_PASSWORD")).build();

    assertEquals(3, config.getAllowedKeys().size());
    assertEquals(3, config.getDeniedKeys().size());
  }

  @Test
  public void testStrictModeEnforcesConstraints() {
    // Strict mode should auto-enable audit logging
    SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.builder().strictMode(true)
        .auditLogging(false) // Try to disable
        .build();

    assertTrue(config.isStrictMode());
    assertTrue(config.isAuditLogging()); // Should be auto-enabled
    assertFalse(config.isAllowSensitiveAccess()); // Should be disabled in strict mode
  }

  @Test
  public void testStrictModeRejectsSensitiveAccess() {
    assertThrows(IllegalArgumentException.class, () -> {
      SystemPropertiesSecurityConfig.builder().strictMode(true).allowSensitiveAccess(true) // Should fail
          .build();
    });
  }

  @Test
  public void testFilterIsConfigured() {
    SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.builder().allowKey("java.*").denyKey("AWS_*")
        .addSensitiveKeyword("internal").build();

    assertNotNull(config.getFilter());

    // Test that filter respects configuration
    assertFalse(config.getFilter().isSensitive("java.version")); // Allowlisted
    assertTrue(config.getFilter().isSensitive("AWS_KEY")); // Denylisted
    assertTrue(config.getFilter().isSensitive("internal_api")); // Custom keyword
  }

  @Test
  public void testImmutability() {
    SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.builder().allowKey("java.*").denyKey("AWS_*")
        .build();

    // Get collections
    Set<String> allowedKeys = config.getAllowedKeys();
    Set<String> deniedKeys = config.getDeniedKeys();

    // Verify they are immutable
    assertThrows(UnsupportedOperationException.class, () -> allowedKeys.add("new.key"));
    assertThrows(UnsupportedOperationException.class, () -> deniedKeys.add("NEW_*"));
  }

  @Test
  public void testNullAndEmptyHandling() {
    // Should handle null/empty gracefully
    SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.builder().allowKey(null).allowKey("")
        .denyKey(null).denyKey("").addSensitiveKeyword(null).addSensitiveKeyword("").build();

    // Should not add null/empty values
    assertTrue(config.getAllowedKeys().isEmpty());
    assertTrue(config.getDeniedKeys().isEmpty());
    assertTrue(config.getCustomSensitiveKeywords().isEmpty());
  }
}
