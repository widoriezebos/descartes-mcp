package com.bitsapplied.descartes.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests for SensitiveDataFilter.
 */
public class SensitiveDataFilterTest {

  @Test
  public void testDefaultSensitiveKeywords() {
    SensitiveDataFilter filter = new SensitiveDataFilter();

    // Should detect default sensitive patterns
    assertTrue(filter.isSensitive("my_password"));
    assertTrue(filter.isSensitive("api_secret"));
    assertTrue(filter.isSensitive("auth_token"));
    assertTrue(filter.isSensitive("access_key"));
    assertTrue(filter.isSensitive("user_credential"));
    assertTrue(filter.isSensitive("database_url"));

    // Should not flag non-sensitive keys
    assertFalse(filter.isSensitive("java.version"));
    assertFalse(filter.isSensitive("os.name"));
    assertFalse(filter.isSensitive("user.dir"));
  }

  @Test
  public void testCaseInsensitivity() {
    SensitiveDataFilter filter = new SensitiveDataFilter();

    assertTrue(filter.isSensitive("PASSWORD"));
    assertTrue(filter.isSensitive("Password"));
    assertTrue(filter.isSensitive("API_SECRET"));
    assertTrue(filter.isSensitive("Api_Secret"));
  }

  @Test
  public void testAllowlistPattern() {
    SensitiveDataFilter filter = SensitiveDataFilter.builder().withAllowlist(Set.of("java.*", "os.name")).build();

    // Allowlist should permit these even if they match sensitive patterns
    assertFalse(filter.isSensitive("java.home"));
    assertFalse(filter.isSensitive("java.version"));
    assertFalse(filter.isSensitive("os.name"));

    // Should still filter non-allowlisted sensitive keys
    assertTrue(filter.isSensitive("my_password"));
    assertTrue(filter.isSensitive("api_secret"));
  }

  @Test
  public void testDenylistPattern() {
    SensitiveDataFilter filter = SensitiveDataFilter.builder().withDenylist(Set.of("AWS_*", "*_PASSWORD", "DB_*"))
        .build();

    // Denylist should filter these
    assertTrue(filter.isSensitive("AWS_ACCESS_KEY"));
    assertTrue(filter.isSensitive("AWS_SECRET"));
    assertTrue(filter.isSensitive("MY_PASSWORD"));
    assertTrue(filter.isSensitive("DB_CONNECTION"));
    assertTrue(filter.isSensitive("DB_HOST"));

    // Should not filter non-denied keys (unless they match sensitive patterns)
    assertFalse(filter.isSensitive("java.version"));
    assertFalse(filter.isSensitive("os.name"));
  }

  @Test
  public void testDenylistOverridesAllowlist() {
    SensitiveDataFilter filter = SensitiveDataFilter.builder().withAllowlist(Set.of("AWS_*"))
        .withDenylist(Set.of("*_SECRET")).build();

    // Denylist should override allowlist
    assertTrue(filter.isSensitive("AWS_SECRET"));

    // Allowlist should still work for non-denied items
    assertFalse(filter.isSensitive("AWS_REGION"));
  }

  @Test
  public void testWildcardPatterns() {
    // Use patterns that won't overlap with default sensitive keywords
    SensitiveDataFilter filter = SensitiveDataFilter.builder().withSensitiveKeywords(Set.of()) // Clear default keywords
                                                                                               // to test patterns only
        .withDenylist(Set.of("AWS_*", "MY_?ASS", "CONFIG_*_VALUE")).build();

    // Star wildcard (zero or more characters)
    assertTrue(filter.isSensitive("AWS_KEY"));
    assertTrue(filter.isSensitive("AWS_ACCESS_KEY_ID"));
    assertTrue(filter.isSensitive("CONFIG_DB_VALUE"));
    assertTrue(filter.isSensitive("CONFIG_API_VALUE"));

    // Question mark wildcard (exactly one character)
    assertTrue(filter.isSensitive("MY_PASS")); // Matches MY_?ASS (? = P)
    assertTrue(filter.isSensitive("MY_CASS")); // Matches MY_?ASS (? = C)
    assertFalse(filter.isSensitive("MY_ASS")); // Doesn't match MY_?ASS (missing the ? character)
    assertFalse(filter.isSensitive("OTHER_VALUE")); // Doesn't match any pattern
  }

  @Test
  public void testCustomSensitiveKeywords() {
    SensitiveDataFilter filter = SensitiveDataFilter.builder().addSensitiveKeyword("internal")
        .addSensitiveKeyword("confidential").build();

    // Should detect custom keywords in addition to defaults
    assertTrue(filter.isSensitive("internal_api"));
    assertTrue(filter.isSensitive("confidential_data"));

    // Should still detect default keywords
    assertTrue(filter.isSensitive("my_password"));
  }

  @Test
  public void testFilterValue() {
    SensitiveDataFilter filter = new SensitiveDataFilter();

    assertEquals("***FILTERED***", filter.filterValue("password", "secret123"));
    assertEquals("***FILTERED***", filter.filterValue("api_token", "abc123"));
    assertEquals("OpenJDK", filter.filterValue("java.vendor", "OpenJDK"));
    assertEquals("Linux", filter.filterValue("os.name", "Linux"));
  }

  @Test
  public void testIsAllowed() {
    SensitiveDataFilter filter = new SensitiveDataFilter();

    assertTrue(filter.isAllowed("java.version"));
    assertTrue(filter.isAllowed("os.name"));
    assertFalse(filter.isAllowed("password"));
    assertFalse(filter.isAllowed("api_secret"));
  }

  @Test
  public void testComplexScenario() {
    // Production-like configuration: strict denylist, allowlist for safe keys
    SensitiveDataFilter filter = SensitiveDataFilter.builder().withAllowlist(Set.of("java.*", "os.*", "user.timezone"))
        .withDenylist(Set.of("AWS_*", "DB_*", "*_PASSWORD", "*_SECRET", "*_TOKEN", "*_KEY", "*_CREDENTIAL"))
        .withAuditLogging(false) // Disabled for test
        .build();

    // Allowed safe properties
    assertFalse(filter.isSensitive("java.version"));
    assertFalse(filter.isSensitive("java.vendor"));
    assertFalse(filter.isSensitive("os.name"));
    assertFalse(filter.isSensitive("os.arch"));
    assertFalse(filter.isSensitive("user.timezone"));

    // Denied sensitive properties
    assertTrue(filter.isSensitive("AWS_ACCESS_KEY_ID"));
    assertTrue(filter.isSensitive("AWS_SECRET_ACCESS_KEY"));
    assertTrue(filter.isSensitive("DB_PASSWORD"));
    assertTrue(filter.isSensitive("DB_HOST"));
    assertTrue(filter.isSensitive("GITHUB_TOKEN"));
    assertTrue(filter.isSensitive("API_KEY"));
    assertTrue(filter.isSensitive("USER_CREDENTIAL"));

    // Properties not in allowlist and matching sensitive patterns
    assertTrue(filter.isSensitive("my.custom.password"));
    assertTrue(filter.isSensitive("app.secret.value"));
  }

  @Test
  public void testEmptyPatterns() {
    SensitiveDataFilter filter = SensitiveDataFilter.builder().withAllowlist(Set.of()).withDenylist(Set.of()).build();

    // Should still use default sensitive keywords
    assertTrue(filter.isSensitive("password"));
    assertFalse(filter.isSensitive("java.version"));
  }

  @Test
  public void testNullAndEmptyKeys() {
    SensitiveDataFilter filter = new SensitiveDataFilter();

    assertFalse(filter.isSensitive(null));
    assertFalse(filter.isSensitive(""));
  }

  @Test
  public void testRegexSpecialCharacters() {
    // Test that regex special characters are properly escaped
    // Use patterns that won't overlap with default sensitive keywords
    SensitiveDataFilter filter = SensitiveDataFilter.builder().withSensitiveKeywords(Set.of()) // Clear defaults to test
                                                                                               // patterns only
        .withDenylist(Set.of("config.value", "app[test]", "data(prod)")).build();

    // Should match exact patterns with escaped special chars
    assertTrue(filter.isSensitive("config.value"));
    assertTrue(filter.isSensitive("app[test]"));
    assertTrue(filter.isSensitive("data(prod)"));

    // Should not match if pattern is different
    assertFalse(filter.isSensitive("config_value")); // Underscore instead of dot
    assertFalse(filter.isSensitive("apptest")); // Missing the brackets
    assertFalse(filter.isSensitive("dataprod")); // Missing the parentheses
  }
}
