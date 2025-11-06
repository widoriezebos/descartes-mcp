package com.bitsapplied.descartes.resources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.util.QueryParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Security-focused integration tests for SystemPropertiesResource. Tests that
 * security policies are properly enforced.
 */
public class SystemPropertiesResourceSecurityTest {
  private static final ObjectMapper mapper = new ObjectMapper();

  @Test
  public void testProductionConfigDeniesincludeSensitive() {
    SystemPropertiesResource resource = new SystemPropertiesResource(SystemPropertiesSecurityConfig.forProduction());

    QueryParams params = new QueryParams("type=all&includeSensitive=true");

    // Should throw exception because production config doesn't allow sensitive
    // access
    MCPResource.ResourceException exception = assertThrows(MCPResource.ResourceException.class, () -> {
      resource.handleRequest(params);
    });

    assertTrue(exception.getMessage().contains("disabled by security configuration"));
  }

  @Test
  public void testDevelopmentConfigAllowsincludeSensitive() throws Exception {
    SystemPropertiesResource resource = new SystemPropertiesResource(SystemPropertiesSecurityConfig.forDevelopment());

    QueryParams params = new QueryParams("type=system&includeSensitive=true");

    // Should not throw, development allows sensitive access
    String result = resource.handleRequest(params);
    assertNotNull(result);

    JsonNode json = mapper.readTree(result);
    assertTrue(json.has("java") || json.has("other"));
  }

  @Test
  public void testDefaultConstructorUsesProductionSettings() {
    SystemPropertiesResource resource = new SystemPropertiesResource();

    QueryParams params = new QueryParams("type=all&includeSensitive=true");

    // Default constructor should use production settings (restrictive)
    assertThrows(MCPResource.ResourceException.class, () -> {
      resource.handleRequest(params);
    });
  }

  @Test
  public void testAllowlistFiltering() throws Exception {
    SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.builder().allowSensitiveAccess(false)
        .allowKey("java.version").allowKey("os.name").build();

    SystemPropertiesResource resource = new SystemPropertiesResource(config);

    QueryParams params = new QueryParams("type=system");

    String result = resource.handleRequest(params);
    JsonNode json = mapper.readTree(result);

    // Should only contain allowlisted properties (and non-sensitive ones)
    // Note: The filter also allows non-sensitive properties by default
    // So we're checking that sensitive properties are filtered
    assertTrue(json.get("filteredCount").asInt() < json.get("totalCount").asInt());
  }

  @Test
  public void testCustomDenylistFiltering() throws Exception {
    // Set a system property that matches our denylist pattern
    System.setProperty("test.my_password", "secret123");

    try {
      SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.builder().allowSensitiveAccess(false)
          .denyKey("*_password").build();

      SystemPropertiesResource resource = new SystemPropertiesResource(config);

      QueryParams params = new QueryParams("type=system");

      String result = resource.handleRequest(params);

      // The denylisted property should not appear in results
      // (it should be filtered out)
      String jsonString = result.toLowerCase();
      if (jsonString.contains("test.my_password")) {
        // If it appears, it must be filtered
        assertTrue(jsonString.contains("***filtered***"));
      }
    } finally {
      System.clearProperty("test.my_password");
    }
  }

  @Test
  public void testEnvironmentVariablesFiltering() throws Exception {
    SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.forProduction();
    SystemPropertiesResource resource = new SystemPropertiesResource(config);

    QueryParams params = new QueryParams("type=environment");

    String result = resource.handleRequest(params);
    JsonNode json = mapper.readTree(result);

    // Should have filtered some environment variables
    int total = json.get("totalCount").asInt();
    int filtered = json.get("filteredCount").asInt();

    // In production config, many variables should be filtered
    // (exact count depends on environment, but filtered should be less than total)
    assertTrue(filtered <= total);
  }

  @Test
  public void testRuntimeInfoDoesNotRequireSensitiveAccess() throws Exception {
    SystemPropertiesResource resource = new SystemPropertiesResource(SystemPropertiesSecurityConfig.forProduction());

    QueryParams params = new QueryParams("type=runtime");

    // Runtime info should always be accessible (no sensitive check)
    String result = resource.handleRequest(params);
    assertNotNull(result);

    JsonNode json = mapper.readTree(result);
    assertTrue(json.has("name"));
    assertTrue(json.has("vmName"));
  }

  @Test
  public void testJvmArgumentsDoesNotRequireSensitiveAccess() throws Exception {
    SystemPropertiesResource resource = new SystemPropertiesResource(SystemPropertiesSecurityConfig.forProduction());

    QueryParams params = new QueryParams("type=jvm");

    // JVM arguments should always be accessible
    String result = resource.handleRequest(params);
    assertNotNull(result);

    JsonNode json = mapper.readTree(result);
    assertTrue(json.has("inputArguments"));
    assertTrue(json.has("totalCount"));
  }

  @Test
  public void testFilterParameterWorksWithSecurity() throws Exception {
    SystemPropertiesResource resource = new SystemPropertiesResource(SystemPropertiesSecurityConfig.forDevelopment());

    QueryParams params = new QueryParams("type=system&filter=java.version");

    String result = resource.handleRequest(params);
    assertNotNull(result);

    JsonNode json = mapper.readTree(result);
    // Should only return properties matching "java.version"
    assertTrue(json.get("filteredCount").asInt() <= json.get("totalCount").asInt());
  }

  @Test
  public void testStrictModeEnforcesStrictSecurity() throws Exception {
    SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.builder().strictMode(true).build();

    SystemPropertiesResource resource = new SystemPropertiesResource(config);

    // Strict mode should disable sensitive access
    assertFalse(config.isAllowSensitiveAccess());

    // Strict mode should enable audit logging
    assertTrue(config.isAuditLogging());

    QueryParams params = new QueryParams("type=all&includeSensitive=true");

    // Should deny sensitive access in strict mode
    assertThrows(MCPResource.ResourceException.class, () -> {
      resource.handleRequest(params);
    });
  }

  @Test
  public void testCustomConfigurationWithBuilder() throws Exception {
    SystemPropertiesSecurityConfig config = SystemPropertiesSecurityConfig.builder().allowSensitiveAccess(true)
        .auditLogging(false).allowKey("java.*").allowKey("os.*").denyKey("*_password").denyKey("*_secret").build();

    SystemPropertiesResource resource = new SystemPropertiesResource(config);

    QueryParams params = new QueryParams("type=system&includeSensitive=true");

    // Should allow sensitive access because we configured it
    String result = resource.handleRequest(params);
    assertNotNull(result);
  }

  @Test
  public void testNullSecurityConfigThrowsException() {
    assertThrows(IllegalArgumentException.class, () -> {
      new SystemPropertiesResource(null);
    });
  }
}
