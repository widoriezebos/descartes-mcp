package com.bitsapplied.descartes.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.util.ParameterValidator.ValidationException;

/**
 * Tests for ParameterValidator utility class.
 *
 * <p>
 * Verifies all validation methods handle valid inputs correctly and throw
 * appropriate ValidationExceptions for invalid inputs with clear error
 * messages.
 */
public class ParameterValidatorTest {

  // ========== Required String Tests ==========

  @Test
  public void testRequireString_Valid() {
    Map<String, Object> params = Map.of("name", "value");
    assertEquals("value", ParameterValidator.requireString(params, "name"));
  }

  @Test
  public void testRequireString_Missing() {
    Map<String, Object> params = new HashMap<>();
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.requireString(params, "name"));
    assertTrue(ex.getMessage().contains("required"));
  }

  @Test
  public void testRequireString_Null() {
    Map<String, Object> params = new HashMap<>();
    params.put("name", null);
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.requireString(params, "name"));
    assertTrue(ex.getMessage().contains("required"));
  }

  @Test
  public void testRequireString_WrongType() {
    Map<String, Object> params = Map.of("name", 123);
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.requireString(params, "name"));
    assertTrue(ex.getMessage().contains("must be a string"));
  }

  @Test
  public void testRequireNonEmptyString_Valid() {
    Map<String, Object> params = Map.of("name", "value");
    assertEquals("value", ParameterValidator.requireNonEmptyString(params, "name"));
  }

  @Test
  public void testRequireNonEmptyString_Empty() {
    Map<String, Object> params = Map.of("name", "");
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.requireNonEmptyString(params, "name"));
    assertTrue(ex.getMessage().contains("must not be empty"));
  }

  @Test
  public void testRequireNonEmptyString_Whitespace() {
    Map<String, Object> params = Map.of("name", "   ");
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.requireNonEmptyString(params, "name"));
    assertTrue(ex.getMessage().contains("must not be empty"));
  }

  // ========== Required Integer Tests ==========

  @Test
  public void testRequireInt_ValidNumber() {
    Map<String, Object> params = Map.of("count", 42);
    assertEquals(42, ParameterValidator.requireInt(params, "count"));
  }

  @Test
  public void testRequireInt_ValidLong() {
    Map<String, Object> params = Map.of("count", 42L);
    assertEquals(42, ParameterValidator.requireInt(params, "count"));
  }

  @Test
  public void testRequireInt_ValidString() {
    Map<String, Object> params = Map.of("count", "42");
    assertEquals(42, ParameterValidator.requireInt(params, "count"));
  }

  @Test
  public void testRequireInt_Missing() {
    Map<String, Object> params = new HashMap<>();
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.requireInt(params, "count"));
    assertTrue(ex.getMessage().contains("required"));
  }

  @Test
  public void testRequireInt_InvalidString() {
    Map<String, Object> params = Map.of("count", "not a number");
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.requireInt(params, "count"));
    assertTrue(ex.getMessage().contains("valid integer"));
  }

  // ========== Required Long Tests ==========

  @Test
  public void testRequireLong_Valid() {
    Map<String, Object> params = Map.of("id", 123456789L);
    assertEquals(123456789L, ParameterValidator.requireLong(params, "id"));
  }

  @Test
  public void testRequireLong_ValidString() {
    Map<String, Object> params = Map.of("id", "123456789");
    assertEquals(123456789L, ParameterValidator.requireLong(params, "id"));
  }

  // ========== Required Boolean Tests ==========

  @Test
  public void testRequireBoolean_Valid() {
    Map<String, Object> params = Map.of("flag", true);
    assertTrue(ParameterValidator.requireBoolean(params, "flag"));
  }

  @Test
  public void testRequireBoolean_False() {
    Map<String, Object> params = Map.of("flag", false);
    assertFalse(ParameterValidator.requireBoolean(params, "flag"));
  }

  @Test
  public void testRequireBoolean_WrongType() {
    Map<String, Object> params = Map.of("flag", "true");
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.requireBoolean(params, "flag"));
    assertTrue(ex.getMessage().contains("must be a boolean"));
  }

  // ========== Optional String Tests ==========

  @Test
  public void testOptionalString_Present() {
    Map<String, Object> params = Map.of("name", "value");
    assertEquals("value", ParameterValidator.optionalString(params, "name", "default"));
  }

  @Test
  public void testOptionalString_Missing() {
    Map<String, Object> params = new HashMap<>();
    assertEquals("default", ParameterValidator.optionalString(params, "name", "default"));
  }

  @Test
  public void testOptionalString_Null() {
    Map<String, Object> params = new HashMap<>();
    params.put("name", null);
    assertEquals("default", ParameterValidator.optionalString(params, "name", "default"));
  }

  @Test
  public void testOptionalString_WrongType() {
    Map<String, Object> params = Map.of("name", 123);
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.optionalString(params, "name", "default"));
    assertTrue(ex.getMessage().contains("must be a string"));
  }

  // ========== Optional Integer Tests ==========

  @Test
  public void testOptionalInt_Present() {
    Map<String, Object> params = Map.of("count", 42);
    assertEquals(42, ParameterValidator.optionalInt(params, "count", 10));
  }

  @Test
  public void testOptionalInt_Missing() {
    Map<String, Object> params = new HashMap<>();
    assertEquals(10, ParameterValidator.optionalInt(params, "count", 10));
  }

  // ========== Optional Long Tests ==========

  @Test
  public void testOptionalLong_Present() {
    Map<String, Object> params = Map.of("id", 123456789L);
    assertEquals(123456789L, ParameterValidator.optionalLong(params, "id", 0L));
  }

  @Test
  public void testOptionalLong_Missing() {
    Map<String, Object> params = new HashMap<>();
    assertEquals(999L, ParameterValidator.optionalLong(params, "id", 999L));
  }

  // ========== Optional Boolean Tests ==========

  @Test
  public void testOptionalBoolean_Present() {
    Map<String, Object> params = Map.of("flag", true);
    assertTrue(ParameterValidator.optionalBoolean(params, "flag", false));
  }

  @Test
  public void testOptionalBoolean_Missing() {
    Map<String, Object> params = new HashMap<>();
    assertFalse(ParameterValidator.optionalBoolean(params, "flag", false));
  }

  // ========== Enum Validation Tests ==========

  @Test
  public void testRequireEnum_Valid_Set() {
    Map<String, Object> params = Map.of("operation", "start");
    Set<String> allowed = Set.of("start", "stop", "status");
    assertEquals("start", ParameterValidator.requireEnum(params, "operation", allowed));
  }

  @Test
  public void testRequireEnum_Valid_List() {
    Map<String, Object> params = Map.of("operation", "stop");
    List<String> allowed = List.of("start", "stop", "status");
    assertEquals("stop", ParameterValidator.requireEnum(params, "operation", allowed));
  }

  @Test
  public void testRequireEnum_Invalid() {
    Map<String, Object> params = Map.of("operation", "invalid");
    Set<String> allowed = Set.of("start", "stop", "status");
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.requireEnum(params, "operation", allowed));
    assertTrue(ex.getMessage().contains("must be one of"));
    assertTrue(ex.getMessage().contains("invalid"));
  }

  // ========== Range Validation Tests ==========

  @Test
  public void testRequireIntInRange_Valid() {
    Map<String, Object> params = Map.of("port", 8080);
    assertEquals(8080, ParameterValidator.requireIntInRange(params, "port", 1024, 65535));
  }

  @Test
  public void testRequireIntInRange_TooLow() {
    Map<String, Object> params = Map.of("port", 100);
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.requireIntInRange(params, "port", 1024, 65535));
    assertTrue(ex.getMessage().contains("between"));
    assertTrue(ex.getMessage().contains("100"));
  }

  @Test
  public void testRequireIntInRange_TooHigh() {
    Map<String, Object> params = Map.of("port", 99999);
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.requireIntInRange(params, "port", 1024, 65535));
    assertTrue(ex.getMessage().contains("between"));
  }

  @Test
  public void testOptionalIntInRange_Present() {
    Map<String, Object> params = Map.of("timeout", 5000);
    assertEquals(5000, ParameterValidator.optionalIntInRange(params, "timeout", 1000, 10000, 3000));
  }

  @Test
  public void testOptionalIntInRange_Missing() {
    Map<String, Object> params = new HashMap<>();
    assertEquals(3000, ParameterValidator.optionalIntInRange(params, "timeout", 1000, 10000, 3000));
  }

  @Test
  public void testOptionalIntInRange_OutOfRange() {
    Map<String, Object> params = Map.of("timeout", 500);
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.optionalIntInRange(params, "timeout", 1000, 10000, 3000));
    assertTrue(ex.getMessage().contains("between"));
  }

  // ========== Optional (Nullable) Tests ==========

  @Test
  public void testGetOptionalString_Present() {
    Map<String, Object> params = Map.of("name", "value");
    Optional<String> result = ParameterValidator.getOptionalString(params, "name");
    assertTrue(result.isPresent());
    assertEquals("value", result.get());
  }

  @Test
  public void testGetOptionalString_Missing() {
    Map<String, Object> params = new HashMap<>();
    Optional<String> result = ParameterValidator.getOptionalString(params, "name");
    assertFalse(result.isPresent());
  }

  @Test
  public void testGetOptionalString_Null() {
    Map<String, Object> params = new HashMap<>();
    params.put("name", null);
    Optional<String> result = ParameterValidator.getOptionalString(params, "name");
    assertFalse(result.isPresent());
  }

  @Test
  public void testGetOptionalInt_Present() {
    Map<String, Object> params = Map.of("count", 42);
    Optional<Integer> result = ParameterValidator.getOptionalInt(params, "count");
    assertTrue(result.isPresent());
    assertEquals(42, result.get());
  }

  @Test
  public void testGetOptionalInt_Missing() {
    Map<String, Object> params = new HashMap<>();
    Optional<Integer> result = ParameterValidator.getOptionalInt(params, "count");
    assertFalse(result.isPresent());
  }

  @Test
  public void testGetOptionalLong_Present() {
    Map<String, Object> params = Map.of("id", 123456789L);
    Optional<Long> result = ParameterValidator.getOptionalLong(params, "id");
    assertTrue(result.isPresent());
    assertEquals(123456789L, result.get());
  }

  @Test
  public void testGetOptionalLong_Missing() {
    Map<String, Object> params = new HashMap<>();
    Optional<Long> result = ParameterValidator.getOptionalLong(params, "id");
    assertFalse(result.isPresent());
  }

  // ========== Error Message Quality Tests ==========

  @Test
  public void testErrorMessagesIncludeParameterName() {
    Map<String, Object> params = new HashMap<>();
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.requireString(params, "custom_param"));
    assertTrue(ex.getMessage().contains("custom_param"));
  }

  @Test
  public void testErrorMessagesIncludeActualValue() {
    Map<String, Object> params = Map.of("port", 999999);
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.requireIntInRange(params, "port", 1024, 65535));
    assertTrue(ex.getMessage().contains("999999"));
  }

  @Test
  public void testErrorMessagesIncludeAllowedValues() {
    Map<String, Object> params = Map.of("operation", "invalid");
    Set<String> allowed = Set.of("start", "stop");
    ValidationException ex = assertThrows(ValidationException.class,
        () -> ParameterValidator.requireEnum(params, "operation", allowed));
    assertTrue(ex.getMessage().contains("start"));
    assertTrue(ex.getMessage().contains("stop"));
  }
}
